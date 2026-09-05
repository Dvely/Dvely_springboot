package com.example.dvely.provisioning.infrastructure;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver.AwsAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.Aliases;
import software.amazon.awssdk.services.cloudfront.model.AllowedMethods;
import software.amazon.awssdk.services.cloudfront.model.CachedMethods;
import software.amazon.awssdk.services.cloudfront.model.CreateDistributionWithTagsRequest;
import software.amazon.awssdk.services.cloudfront.model.CustomErrorResponse;
import software.amazon.awssdk.services.cloudfront.model.CustomErrorResponses;
import software.amazon.awssdk.services.cloudfront.model.CustomOriginConfig;
import software.amazon.awssdk.services.cloudfront.model.DefaultCacheBehavior;
import software.amazon.awssdk.services.cloudfront.model.DeleteDistributionRequest;
import software.amazon.awssdk.services.cloudfront.model.Distribution;
import software.amazon.awssdk.services.cloudfront.model.DistributionConfig;
import software.amazon.awssdk.services.cloudfront.model.DistributionConfigWithTags;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionConfigResponse;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionResponse;
import software.amazon.awssdk.services.cloudfront.model.Method;
import software.amazon.awssdk.services.cloudfront.model.MinimumProtocolVersion;
import software.amazon.awssdk.services.cloudfront.model.Origin;
import software.amazon.awssdk.services.cloudfront.model.OriginProtocolPolicy;
import software.amazon.awssdk.services.cloudfront.model.OriginSslProtocols;
import software.amazon.awssdk.services.cloudfront.model.Origins;
import software.amazon.awssdk.services.cloudfront.model.SslProtocol;
import software.amazon.awssdk.services.cloudfront.model.SSLSupportMethod;
import software.amazon.awssdk.services.cloudfront.model.Tag;
import software.amazon.awssdk.services.cloudfront.model.Tags;
import software.amazon.awssdk.services.cloudfront.model.UpdateDistributionRequest;
import software.amazon.awssdk.services.cloudfront.model.ViewerCertificate;
import software.amazon.awssdk.services.cloudfront.model.ViewerProtocolPolicy;

/**
 * S3 website(http-only) 엔드포인트를 커스텀 오리진으로 CloudFront 배포를 만들어 사용자 도메인에 HTTPS 를
 * 종단한다. CloudFront 는 글로벌 서비스라 {@link Region#AWS_GLOBAL} 클라이언트로 호출한다. 뷰어는
 * https(redirect-to-https), 오리진과는 http(S3 website 가 http-only). SPA 폴백은 403/404 → 200 /index.html.
 *
 * <p>배포 삭제는 <b>disable → Deployed 대기 → delete</b> 의 다단계다(수 분). 그래서 즉시 못 지우고 리퍼
 * 워커가 마무리한다({@code CdnDeletionReaper}). CreateDistribution 은 멱등이 아니므로 호출부는 배포 id 를
 * 즉시 저장해 재생성을 막아야 한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CloudFrontDistributionProvisioner {

    // AWS 관리형 캐시 정책 "CachingOptimized" — 정적 사이트 캐싱에 적합, 정책 id 는 전 계정 공통 상수.
    private static final String MANAGED_CACHING_OPTIMIZED = "658327ea-f89d-4fab-a63d-7e88639e58f6";
    private static final String ORIGIN_ID = "s3-website-origin";

    private final AwsCredentialsResolver credentialsResolver;

    /**
     * 배포 생성. hostname=별칭(뷰어 도메인), certificateArn=그 도메인용 us-east-1 ACM 인증서,
     * originHost=S3 website 엔드포인트 호스트(http-only). 반환은 배포 id + CloudFront 도메인(dxxx.cloudfront.net).
     */
    public DistributionInfo createDistribution(CloudConnection connection,
                                               String hostname,
                                               String certificateArn,
                                               String originHost) {
        try (CloudFrontClient cf = client(connection)) {
            DistributionConfig config = distributionConfig(hostname, certificateArn, originHost);
            Distribution distribution = cf.createDistributionWithTags(CreateDistributionWithTagsRequest.builder()
                    .distributionConfigWithTags(DistributionConfigWithTags.builder()
                            .distributionConfig(config)
                            .tags(Tags.builder().items(
                                    Tag.builder().key("managed-by").value("qeploy").build()).build())
                            .build())
                    .build()).distribution();
            log.info("CloudFront 배포 생성: hostname={} distributionId={} domain={}",
                    hostname, distribution.id(), distribution.domainName());
            return new DistributionInfo(distribution.id(), distribution.domainName());
        }
    }

    /** 배포 상태(enabled·Deployed). Deployed 여야 삭제할 수 있고, https 서빙 준비가 됐다는 신호다. */
    public DistributionState getState(CloudConnection connection, String distributionId) {
        try (CloudFrontClient cf = client(connection)) {
            GetDistributionResponse resp = cf.getDistribution(r -> r.id(distributionId));
            Distribution distribution = resp.distribution();
            boolean enabled = distribution.distributionConfig().enabled();
            boolean deployed = "Deployed".equalsIgnoreCase(distribution.status());
            return new DistributionState(enabled, deployed);
        }
    }

    /** 배포 비활성화(enabled=false). 삭제 전 필수 단계. 이미 비활성이면 no-op 에 가깝다(멱등적으로 다시 설정). */
    public void disable(CloudConnection connection, String distributionId) {
        try (CloudFrontClient cf = client(connection)) {
            GetDistributionConfigResponse current = cf.getDistributionConfig(r -> r.id(distributionId));
            if (Boolean.FALSE.equals(current.distributionConfig().enabled())) {
                return;
            }
            DistributionConfig disabled = current.distributionConfig().toBuilder().enabled(false).build();
            cf.updateDistribution(UpdateDistributionRequest.builder()
                    .id(distributionId)
                    .ifMatch(current.eTag())
                    .distributionConfig(disabled)
                    .build());
            log.info("CloudFront 배포 비활성화: distributionId={}", distributionId);
        }
    }

    /** 배포 삭제. 비활성 + Deployed 상태여야 성공한다(아니면 예외). */
    public void delete(CloudConnection connection, String distributionId) {
        try (CloudFrontClient cf = client(connection)) {
            String etag = cf.getDistributionConfig(r -> r.id(distributionId)).eTag();
            cf.deleteDistribution(DeleteDistributionRequest.builder()
                    .id(distributionId).ifMatch(etag).build());
            log.info("CloudFront 배포 삭제: distributionId={}", distributionId);
        }
    }

    private DistributionConfig distributionConfig(String hostname, String certificateArn, String originHost) {
        return DistributionConfig.builder()
                .callerReference("qeploy-" + hostname)   // hostname 당 하나 — 재호출 시 중복생성 대신 에러(호출부가 id 저장으로 방지)
                .comment("qeploy S3 front HTTPS: " + hostname)
                .enabled(true)
                .aliases(Aliases.builder().quantity(1).items(hostname).build())
                .defaultRootObject("index.html")
                .origins(Origins.builder().quantity(1).items(Origin.builder()
                        .id(ORIGIN_ID)
                        .domainName(originHost)
                        .customOriginConfig(CustomOriginConfig.builder()
                                .httpPort(80)
                                .httpsPort(443)
                                .originProtocolPolicy(OriginProtocolPolicy.HTTP_ONLY)   // S3 website 는 http-only
                                .originSslProtocols(OriginSslProtocols.builder()
                                        .quantity(1).items(SslProtocol.TLS_V1_2).build())
                                .build())
                        .build()).build())
                .defaultCacheBehavior(DefaultCacheBehavior.builder()
                        .targetOriginId(ORIGIN_ID)
                        .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                        .allowedMethods(AllowedMethods.builder()
                                .quantity(2).items(Method.GET, Method.HEAD)
                                .cachedMethods(CachedMethods.builder()
                                        .quantity(2).items(Method.GET, Method.HEAD).build())
                                .build())
                        .compress(true)
                        .cachePolicyId(MANAGED_CACHING_OPTIMIZED)
                        .build())
                .customErrorResponses(CustomErrorResponses.builder()
                        .quantity(2).items(
                                spaFallback(403),
                                spaFallback(404))
                        .build())
                .viewerCertificate(ViewerCertificate.builder()
                        .acmCertificateArn(certificateArn)
                        .sslSupportMethod(SSLSupportMethod.SNI_ONLY)
                        .minimumProtocolVersion(MinimumProtocolVersion.TLS_V1_2_2021)
                        .build())
                .httpVersion("http2")
                .build();
    }

    /** SPA 폴백: 오리진이 4xx(라우트 없음)면 200 으로 /index.html 을 준다(클라이언트 라우팅). */
    private CustomErrorResponse spaFallback(int errorCode) {
        return CustomErrorResponse.builder()
                .errorCode(errorCode)
                .responseCode("200")
                .responsePagePath("/index.html")
                .errorCachingMinTTL(10L)
                .build();
    }

    private CloudFrontClient client(CloudConnection connection) {
        AwsAccess access = credentialsResolver.resolveInRegion(connection, Region.AWS_GLOBAL);
        return CloudFrontClient.builder()
                .region(access.region())
                .credentialsProvider(access.credentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    public record DistributionInfo(String distributionId, String domainName) {}

    public record DistributionState(boolean enabled, boolean deployed) {}
}
