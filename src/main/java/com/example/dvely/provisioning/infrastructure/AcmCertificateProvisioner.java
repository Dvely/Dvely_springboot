package com.example.dvely.provisioning.infrastructure;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver.AwsAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.acm.model.CertificateDetail;
import software.amazon.awssdk.services.acm.model.DeleteCertificateRequest;
import software.amazon.awssdk.services.acm.model.DescribeCertificateRequest;
import software.amazon.awssdk.services.acm.model.DomainValidation;
import software.amazon.awssdk.services.acm.model.RequestCertificateRequest;
import software.amazon.awssdk.services.acm.model.ResourceRecord;
import software.amazon.awssdk.services.acm.model.ValidationMethod;

/**
 * S3 프론트 HTTPS 용 ACM 인증서를 사용자 계정에 발급·조회·삭제한다. <b>CloudFront 용 인증서는 반드시
 * us-east-1</b> 이라, 연결 리전이 무엇이든 {@link AwsCredentialsResolver#resolveInRegion}로 us-east-1
 * 클라이언트를 만든다. 검증은 DNS 방식 — ACM 이 주는 CNAME 을 우리 Cloudflare 존에 넣으면 자동 검증된다
 * (Route53 불필요). 자격은 매 호출 resolve(assume-role 세션이 짧다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AcmCertificateProvisioner {

    private static final Region ACM_REGION = Region.US_EAST_1;

    private final AwsCredentialsResolver credentialsResolver;

    /** DNS 검증 인증서 발급 요청. 반환은 certificate ARN(검증 레코드는 잠시 뒤 describe 로 나온다). */
    public String requestCertificate(CloudConnection connection, String hostname) {
        try (AcmClient acm = client(connection)) {
            String arn = acm.requestCertificate(RequestCertificateRequest.builder()
                    .domainName(hostname)
                    .validationMethod(ValidationMethod.DNS)
                    .idempotencyToken(idempotencyToken(hostname))
                    .build()).certificateArn();
            log.info("ACM 인증서 요청: hostname={} certArn={}", hostname, arn);
            return arn;
        }
    }

    /** 인증서 상태 + DNS 검증 레코드(첫 도메인). 발급 직후엔 검증 레코드가 아직 null 일 수 있다. */
    public CertInfo describe(CloudConnection connection, String certificateArn) {
        try (AcmClient acm = client(connection)) {
            CertificateDetail detail = acm.describeCertificate(DescribeCertificateRequest.builder()
                    .certificateArn(certificateArn).build()).certificate();
            String status = detail.statusAsString();
            String recordName = null;
            String recordValue = null;
            if (detail.hasDomainValidationOptions()) {
                for (DomainValidation option : detail.domainValidationOptions()) {
                    ResourceRecord record = option.resourceRecord();
                    if (record != null) {
                        recordName = record.name();
                        recordValue = record.value();
                        break;
                    }
                }
            }
            return new CertInfo(status, recordName, recordValue);
        }
    }

    /** 인증서 삭제. CloudFront 배포에 아직 붙어 있으면 실패하므로 배포 삭제 후 호출한다. */
    public void deleteCertificate(CloudConnection connection, String certificateArn) {
        try (AcmClient acm = client(connection)) {
            acm.deleteCertificate(DeleteCertificateRequest.builder()
                    .certificateArn(certificateArn).build());
            log.info("ACM 인증서 삭제: certArn={}", certificateArn);
        }
    }

    public record CertInfo(String status, String validationRecordName, String validationRecordValue) {
        public boolean issued() {
            return "ISSUED".equals(status);
        }

        public boolean failed() {
            return "FAILED".equals(status) || "VALIDATION_TIMED_OUT".equals(status)
                    || "REVOKED".equals(status);
        }

        public boolean hasValidationRecord() {
            return validationRecordName != null && validationRecordValue != null;
        }
    }

    /** ACM idempotency token: 영숫자만, 32자 이하. 같은 hostname 이면 같은 토큰이라 재시도 시 중복 발급 방지. */
    private String idempotencyToken(String hostname) {
        String base = hostname.replaceAll("[^A-Za-z0-9]", "");
        if (base.isEmpty()) {
            base = "cert";
        }
        return base.length() <= 32 ? base : base.substring(0, 32);
    }

    private AcmClient client(CloudConnection connection) {
        AwsAccess access = credentialsResolver.resolveInRegion(connection, ACM_REGION);
        return AcmClient.builder()
                .region(access.region())
                .credentialsProvider(access.credentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
