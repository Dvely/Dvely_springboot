package com.example.dvely.provisioning.infrastructure;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver.AwsAccess;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * 빌드 산출물(jar)을 사용자 AWS 계정의 S3 에 올린다. 샌드박스 컨테이너가 만든 jar 를 EC2 로 넘기는
 * 통로다 — 컨테이너와 인스턴스는 다른 머신이라, S3 를 두고 인스턴스가 자기 IAM 역할로 당겨간다
 * (우리가 인스턴스에 접속할 필요가 없다).
 *
 * <p>버킷 이름은 전역 유일해야 하므로 계정 ID+리전으로 짓는다. 저장 시 서버측 암호화(AES256)를 켠다.
 * 자격은 매 호출 resolve(assume-role 세션이 짧다 — RdsProvisioner 와 동일 원칙).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3ArtifactStore {

    private final AwsCredentialsResolver credentialsResolver;

    /** 이 연결(계정+리전)에서 쓸 아티팩트 버킷 이름. 전역 유일하도록 계정·리전을 넣는다. */
    public String bucketNameFor(CloudConnection connection) {
        // accountId 가 비면 "qeploy-artifacts--{region}" 이 돼 전역 버킷 이름이 계정 간 충돌한다
        // (S3 버킷명은 전역 유일). accountId 없는 연결끼리 같은 이름을 노려 BucketAlreadyExists 로
        // 깨지거나, 최악엔 남의 버킷에 붙는다. 여기서 막아 그런 버킷을 아예 안 만든다.
        String accountId = connection.getAccountId();
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalStateException(
                    "클라우드 연결에 AWS 계정 ID가 없어 배포 아티팩트 버킷을 만들 수 없습니다. "
                            + "클라우드 연결에 12자리 계정 ID를 넣어주세요.");
        }
        return "qeploy-artifacts-" + accountId + "-" + connection.getRegion();
    }

    /** 프로젝트별 jar 키. IAM 정책이 {projectId}/* 로 인스턴스 접근을 좁히므로 이 접두사를 지킨다. */
    public String jarKeyFor(Long projectId) {
        return projectId + "/app.jar";
    }

    /** 버킷이 없으면 만든다(멱등). 이미 있으면 아무것도 안 한다. */
    public void ensureBucket(CloudConnection connection, String bucket) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (S3Client s3 = client(access)) {
            try {
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                return;   // 이미 있다
            } catch (NoSuchBucketException e) {
                // 아래에서 만든다
            } catch (S3Exception e) {
                if (e.statusCode() != 404) throw e;   // 404 만 "없음"으로 본다
            }
            CreateBucketRequest.Builder req = CreateBucketRequest.builder().bucket(bucket);
            // us-east-1 은 LocationConstraint 를 주면 오히려 에러다 — 그 리전만 빼고 건다.
            if (!"us-east-1".equals(connection.getRegion())) {
                req.createBucketConfiguration(CreateBucketConfiguration.builder()
                        .locationConstraint(connection.getRegion()).build());
            }
            s3.createBucket(req.build());
            log.info("S3 아티팩트 버킷 생성: bucket={}", bucket);
        }
    }

    /** jar 파일을 올린다(서버측 암호화). 같은 키면 덮어쓴다. */
    public void uploadJar(CloudConnection connection, String bucket, String key, Path jarFile) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (S3Client s3 = client(access)) {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket).key(key)
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .build(), RequestBody.fromFile(jarFile));
            log.info("S3 jar 업로드: bucket={} key={}", bucket, key);
        }
    }

    /** 정리용 — 배포 종료 시 jar 를 지운다. */
    public void deleteJar(CloudConnection connection, String bucket, String key) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (S3Client s3 = client(access)) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            log.info("S3 jar 삭제: bucket={} key={}", bucket, key);
        }
    }

    private S3Client client(AwsAccess access) {
        return S3Client.builder()
                .region(access.region())
                .credentialsProvider(access.credentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
