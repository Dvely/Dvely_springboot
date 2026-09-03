package com.example.dvely.provisioning.infrastructure;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver.AwsAccess;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

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

    /** S3 멀티파트 최소 파트 크기는 5MB. 8MB 로 잡아 파트 수를 줄이되 소켓 write 는 작게 유지한다. */
    private static final int PART_SIZE = 8 * 1024 * 1024;

    /**
     * jar 파일을 올린다(서버측 암호화). 같은 키면 덮어쓴다. <b>멀티파트로 쪼개 올린다.</b>
     *
     * <p>단일 PutObject 로 수십 MB 를 올리면 macOS + JDK 의 {@code NioSocketImpl} 대용량 단일 소켓
     * write 버그에 걸려 {@code java.net.SocketException: Result too large} 로 실패한다(HTTP 클라이언트
     * 종류와 무관 — 둘 다 내부적으로 {@code sun.nio.ch} 를 쓴다). 실계정에서 54MB jar 로 재현
     * (2026-09-03). 파트를 8MB 로 나누면 각 소켓 write 가 작아 이 버그를 피한다. 대용량 업로드의
     * 정석이기도 하다(중간 실패 시 abort).</p>
     */
    public void uploadJar(CloudConnection connection, String bucket, String key, Path jarFile) {
        AwsAccess access = credentialsResolver.resolve(connection);
        long size;
        try {
            size = Files.size(jarFile);
        } catch (IOException e) {
            throw new IllegalStateException("배포 jar 크기 확인 실패: " + jarFile, e);
        }
        try (S3Client s3 = client(access)) {
            String uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(bucket).key(key)
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .build()).uploadId();
            try (FileChannel ch = FileChannel.open(jarFile)) {
                List<CompletedPart> parts = new ArrayList<>();
                byte[] buf = new byte[PART_SIZE];
                int partNumber = 1;
                long uploaded = 0;
                while (uploaded < size) {
                    int toRead = (int) Math.min(PART_SIZE, size - uploaded);
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, toRead);
                    while (bb.hasRemaining() && ch.read(bb) >= 0) { /* 파트 하나를 다 읽는다 */ }
                    int read = bb.position();
                    var resp = s3.uploadPart(UploadPartRequest.builder()
                            .bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber).build(),
                            RequestBody.fromBytes(Arrays.copyOf(buf, read)));
                    parts.add(CompletedPart.builder().partNumber(partNumber).eTag(resp.eTag()).build());
                    uploaded += read;
                    partNumber++;
                }
                s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                        .bucket(bucket).key(key).uploadId(uploadId)
                        .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                        .build());
                log.info("S3 jar 멀티파트 업로드: bucket={} key={} bytes={} parts={}",
                        bucket, key, size, parts.size());
            } catch (IOException | RuntimeException e) {
                // 실패 시 미완성 멀티파트를 정리한다(안 하면 잔여 파트가 스토리지 과금). abort 권한이
                // 없어도(happy path 는 PutObject 만 필요) 본 오류를 가리지 않게 best-effort 로 둔다.
                try {
                    s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                            .bucket(bucket).key(key).uploadId(uploadId).build());
                } catch (RuntimeException ignore) {
                    log.warn("멀티파트 abort 실패(무시): bucket={} key={} uploadId={}", bucket, key, uploadId);
                }
                throw new IllegalStateException("jar 멀티파트 업로드 실패: " + e.getMessage(), e);
            }
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
