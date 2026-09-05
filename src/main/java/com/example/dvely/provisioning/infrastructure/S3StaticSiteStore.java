package com.example.dvely.provisioning.infrastructure;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver.AwsAccess;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ErrorDocument;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.IndexDocument;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutBucketWebsiteRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.WebsiteConfiguration;

/**
 * 프론트 정적 산출물(dist)을 사용자 AWS 계정의 S3 정적 웹호스팅 버킷에 올린다. GitHub Pages 대신
 * S3 를 프론트 호스팅으로 고른 프로젝트가 쓰는 경로다. 산출물은 러너가 도커 샌드박스에서 빌드해 tar
 * 하나로 넘겨준다({@link com.example.dvely.provisioning.application.service.BackendSourceClone#tarContextOut}
 * 형식) — 여기서는 그 tar 를 풀어 파일별로 올린다.
 *
 * <p>정적 아티팩트 버킷({@link S3ArtifactStore}, {@code qeploy-artifacts-*})과 <b>버킷을 분리</b>한다.
 * 정적 웹은 퍼블릭 읽기가 필요한데, jar·이미지가 든 아티팩트 버킷을 퍼블릭으로 열 수는 없다. 그래서
 * 프로젝트별 전용 버킷({@code qeploy-site-{account}-{region}-{projectId}})을 쓰고, 그 버킷만 퍼블릭
 * 읽기 + website(SPA 폴백) 설정을 건다. 자격은 매 호출 resolve(assume-role 세션이 짧다 —
 * S3ArtifactStore 와 동일 원칙).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3StaticSiteStore {

    private final AwsCredentialsResolver credentialsResolver;

    /**
     * S3 website 엔드포인트가 {@code s3-website-{region}}(대시)인 리전들. 그 외(신설 리전)는
     * {@code s3-website.{region}}(점)이다. AWS 가 리전마다 형식을 달리해 하드코딩할 수밖에 없다 —
     * 틀리면 실제 접속만 404 나므로(빌드·업로드는 성공) 실계정 검증 전까지 안 드러난다.
     */
    private static final Set<String> DASH_WEBSITE_REGIONS = Set.of(
            "us-east-1", "us-west-1", "us-west-2",
            "ap-southeast-1", "ap-southeast-2", "ap-northeast-1",
            "eu-west-1", "sa-east-1", "us-gov-west-1");

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html"), Map.entry("htm", "text/html"),
            Map.entry("js", "application/javascript"), Map.entry("mjs", "application/javascript"),
            Map.entry("css", "text/css"), Map.entry("json", "application/json"),
            Map.entry("map", "application/json"), Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"), Map.entry("ttf", "font/ttf"),
            Map.entry("eot", "application/vnd.ms-fontobject"), Map.entry("txt", "text/plain"),
            Map.entry("xml", "application/xml"), Map.entry("wasm", "application/wasm"),
            Map.entry("webmanifest", "application/manifest+json"));

    /** 프로젝트 전용 정적 사이트 버킷 이름. 전역 유일하도록 계정·리전을 넣고, 프로젝트로 분리한다. */
    public String bucketNameFor(CloudConnection connection, Long projectId) {
        String accountId = connection.getAccountId();
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalStateException(
                    "클라우드 연결에 AWS 계정 ID가 없어 정적 사이트 버킷을 만들 수 없습니다. "
                            + "클라우드 연결에 12자리 계정 ID를 넣어주세요.");
        }
        return "qeploy-site-" + accountId + "-" + connection.getRegion() + "-" + projectId;
    }

    /**
     * 정적 웹호스팅 버킷을 준비한다(멱등). 버킷 생성 → 퍼블릭 접근 차단 해제 → 퍼블릭 읽기 정책 →
     * website(index/error=index.html, SPA 폴백). 순서가 중요하다: 퍼블릭 차단을 먼저 풀지 않으면
     * 퍼블릭 정책 put 이 거부된다.
     */
    public void ensureWebsiteBucket(CloudConnection connection, String bucket) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (S3Client s3 = client(access)) {
            createBucketIfAbsent(s3, connection, bucket);
            s3.putPublicAccessBlock(PutPublicAccessBlockRequest.builder()
                    .bucket(bucket)
                    .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                            .blockPublicAcls(false).ignorePublicAcls(false)
                            .blockPublicPolicy(false).restrictPublicBuckets(false).build())
                    .build());
            s3.putBucketPolicy(PutBucketPolicyRequest.builder()
                    .bucket(bucket)
                    .policy(publicReadPolicy(bucket))
                    .build());
            s3.putBucketWebsite(PutBucketWebsiteRequest.builder()
                    .bucket(bucket)
                    .websiteConfiguration(WebsiteConfiguration.builder()
                            .indexDocument(IndexDocument.builder().suffix("index.html").build())
                            // SPA 라우팅: 없는 경로도 index.html 로 돌려 클라이언트 라우터가 처리하게 한다.
                            .errorDocument(ErrorDocument.builder().key("index.html").build())
                            .build())
                    .build());
            log.info("S3 정적 사이트 버킷 준비: bucket={}", bucket);
        }
    }

    /**
     * dist tar(디렉터리를 {@code tar -C dir .} 로 만든 것)를 풀어 파일별로 올린다. 확장자로 content-type
     * 을 정한다 — 안 정하면 브라우저가 JS·CSS 를 text/plain 으로 받아 SPA 가 뜨지 않는다. 디렉터리
     * 엔트리는 건너뛴다. 사이트 파일은 대체로 작아 단일 PutObject 로 올린다(러너는 리눅스라 macOS
     * 대용량 단일 소켓 write 버그와 무관).
     *
     * @return 올린 파일 수
     */
    public int uploadSiteTar(CloudConnection connection, String bucket, Path tarFile) {
        AwsAccess access = credentialsResolver.resolve(connection);
        int uploaded = 0;
        try (S3Client s3 = client(access);
             InputStream in = Files.newInputStream(tarFile);
             TarArchiveInputStream tin = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = tin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String key = normalizeKey(entry.getName());
                if (key.isEmpty()) {
                    continue;
                }
                byte[] bytes = tin.readAllBytes();   // TarArchiveInputStream 은 현재 엔트리까지만 읽는다
                s3.putObject(PutObjectRequest.builder()
                                .bucket(bucket).key(key)
                                .contentType(contentTypeFor(key))
                                .build(),
                        RequestBody.fromBytes(bytes));
                uploaded++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("정적 사이트 tar 업로드 실패: " + e.getMessage(), e);
        }
        log.info("S3 정적 사이트 업로드 완료: bucket={} files={}", bucket, uploaded);
        return uploaded;
    }

    /**
     * 정적 사이트 버킷을 통째로 지운다(객체 전부 삭제 후 버킷 삭제) — 고아 버킷 방지. 버킷이 없으면
     * 조용히 no-op(멱등). 정리 경로라 호출자가 best-effort 로 감싼다. {@code s3:DeleteBucket} 권한이
     * 필요하다(생성만 하던 정책에 추가 — docs/aws-byoc-permissions.md).
     */
    public void deleteSite(CloudConnection connection, String bucket) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (S3Client s3 = client(access)) {
            try {
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (NoSuchBucketException e) {
                return;   // 이미 없다
            } catch (S3Exception e) {
                if (e.statusCode() == 404) {
                    return;
                }
                throw e;
            }
            // 버킷을 지우려면 먼저 비워야 한다. 페이지네이션으로 전부 삭제(배치 1000개).
            ListObjectsV2Request.Builder listReq = ListObjectsV2Request.builder().bucket(bucket);
            ListObjectsV2Response resp;
            do {
                resp = s3.listObjectsV2(listReq.build());
                List<ObjectIdentifier> ids = resp.contents().stream()
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .toList();
                if (!ids.isEmpty()) {
                    s3.deleteObjects(DeleteObjectsRequest.builder()
                            .bucket(bucket)
                            .delete(Delete.builder().objects(ids).quiet(true).build())
                            .build());
                }
                listReq.continuationToken(resp.nextContinuationToken());
            } while (Boolean.TRUE.equals(resp.isTruncated()));
            s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            log.info("S3 정적 사이트 삭제: bucket={}", bucket);
        }
    }

    /** 정적 사이트 접근 URL(S3 website 엔드포인트). 리전마다 대시/점 형식이 다르다. */
    public String websiteEndpoint(String bucket, String region) {
        String sep = DASH_WEBSITE_REGIONS.contains(region) ? "s3-website-" : "s3-website.";
        return "http://" + bucket + "." + sep + region + ".amazonaws.com";
    }

    private void createBucketIfAbsent(S3Client s3, CloudConnection connection, String bucket) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return;   // 이미 있다
        } catch (NoSuchBucketException e) {
            // 아래에서 만든다
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
        }
        CreateBucketRequest.Builder req = CreateBucketRequest.builder().bucket(bucket);
        if (!"us-east-1".equals(connection.getRegion())) {
            req.createBucketConfiguration(CreateBucketConfiguration.builder()
                    .locationConstraint(connection.getRegion()).build());
        }
        s3.createBucket(req.build());
    }

    private String publicReadPolicy(String bucket) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{"
                + "\"Sid\":\"PublicReadGetObject\","
                + "\"Effect\":\"Allow\","
                + "\"Principal\":\"*\","
                + "\"Action\":\"s3:GetObject\","
                + "\"Resource\":\"arn:aws:s3:::" + bucket + "/*\"}]}";
    }

    /** tar 엔트리명을 S3 키로. 선행 {@code ./} 와 {@code /} 를 벗긴다(버킷 루트 기준 상대경로). */
    private String normalizeKey(String entryName) {
        String key = entryName;
        while (key.startsWith("./") || key.startsWith("/")) {
            key = key.startsWith("./") ? key.substring(2) : key.substring(1);
        }
        return key;
    }

    private String contentTypeFor(String key) {
        int dot = key.lastIndexOf('.');
        int slash = key.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            return "application/octet-stream";
        }
        String ext = key.substring(dot + 1).toLowerCase();
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    private S3Client client(AwsAccess access) {
        return S3Client.builder()
                .region(access.region())
                .credentialsProvider(access.credentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
