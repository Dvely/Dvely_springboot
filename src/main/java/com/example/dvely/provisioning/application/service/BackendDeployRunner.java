package com.example.dvely.provisioning.application.service;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ProvisionFailureCode;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import com.example.dvely.provisioning.infrastructure.Ec2InstanceRoleProvisioner;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner.LaunchSpec;
import com.example.dvely.provisioning.infrastructure.S3ArtifactStore;
import com.example.dvely.provisioning.infrastructure.SsmParameterStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 승인·큐잉된 EC2 서버 배포를 실제로 실행한다(배포 워커가 트랜잭션 밖에서 호출). 순서:
 * 컨테이너 빌드 → jar 를 S3 로 → env 를 SSM SecureString 으로 → 인스턴스 IAM 역할 ensure →
 * 보안그룹·AMI → launch → PROVISIONING. 여기까지가 "생성 시작"이고, running/헬스체크는 상태 워커(C2d)가 마무리한다.
 *
 * <p>실패하면 서버를 FAILED 로 닫는다. launch 이후 저장 단계에서 터지면 방금 만든 인스턴스는 과금이라
 * 즉시 terminate 로 롤백한다(그 전 단계 자원 — S3·SSM·IAM·SG — 은 무과금/재사용이라 남겨도 무해).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackendDeployRunner {

    private static final int LAUNCH_RETRY = 5;          // IAM 최종적 일관성 대비
    private static final long LAUNCH_RETRY_DELAY_MS = 3000;

    private final ProvisionedServerRepository serverRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final BackendJarBuildService buildService;
    private final S3ArtifactStore s3;
    private final SsmParameterStore ssm;
    private final Ec2InstanceRoleProvisioner roleProvisioner;
    private final Ec2Provisioner ec2;
    private final ProvisionedDatabaseRepository databaseRepository;
    private final EnvironmentVariableRepository environmentVariableRepository;

    /** BUILDING 상태로 넘어온 서버를 실제로 배포한다. 성공 시 PROVISIONING, 실패 시 FAILED. */
    public void deploy(ProvisionedServer server) {
        Long projectId = server.getProjectId();
        CloudConnection connection = cloudConnectionRepository.findById(server.getCloudConnectionId())
                .orElse(null);
        if (connection == null) {
            fail(server, ProvisionFailureCode.PROVIDER_ERROR, "클라우드 연결이 없어 배포할 수 없습니다.");
            return;
        }
        Long ownerUserId = connection.getOwnerUserId();
        // 계정 ID가 없으면 뒤늦게 S3 버킷 단계에서 깨지는 대신, 무거운 빌드 전에 바로 실패시킨다
        // (버킷 이름 전역 충돌 방지 — S3ArtifactStore.bucketNameFor 참고).
        if (connection.getAccountId() == null || connection.getAccountId().isBlank()) {
            fail(server, ProvisionFailureCode.PROVIDER_ERROR,
                    "클라우드 연결에 AWS 계정 ID(12자리)가 없습니다. 연결 설정에 계정 ID를 넣어주세요.");
            return;
        }
        Path jar = null;
        String instanceId = null;
        try {
            jar = buildService.buildJar(ownerUserId, projectId);

            String bucket = s3.bucketNameFor(connection);
            String key = s3.jarKeyFor(projectId);
            s3.ensureBucket(connection, bucket);
            s3.uploadJar(connection, bucket, key, jar);

            ssm.putAll(connection, projectId, assembleEnv(projectId, server.getPort()));

            String profileName = roleProvisioner.ensureInstanceProfile(connection, projectId, bucket);
            String sgId = ec2.ensureSecurityGroup(connection, server.getPort());
            String ami = ssm.latestAmazonLinux2023Ami(connection);
            String userData = userDataScript(bucket, key, projectId, server.getPort());

            LaunchSpec spec = new LaunchSpec(server.getInstanceType(), ami, userData, sgId, null,
                    profileName, "qeploy-backend-" + projectId);
            instanceId = launchWithRetry(connection, spec);

            server.beginProvisioning(instanceId);
            serverRepository.save(server);
            log.info("EC2 배포 시작 완료: serverId={} instanceId={} projectId={}",
                    server.getId(), instanceId, projectId);
        } catch (BackendBuildException e) {
            fail(server, ProvisionFailureCode.PROVIDER_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            // launch 이후(인스턴스 생김) 실패면 과금이라 즉시 롤백한다.
            if (instanceId != null) {
                safeTerminate(connection, instanceId);
            }
            fail(server, classify(e), e.getMessage());
        } finally {
            if (jar != null) {
                try { Files.deleteIfExists(jar); } catch (IOException ignored) { }
            }
        }
    }

    private String launchWithRetry(CloudConnection connection, LaunchSpec spec) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= LAUNCH_RETRY; attempt++) {
            try {
                return ec2.launch(connection, spec);
            } catch (RuntimeException e) {
                // 방금 만든 IAM 인스턴스 프로파일이 아직 전파 안 됐을 수 있다(최종적 일관성) — 재시도.
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (attempt < LAUNCH_RETRY && msg.contains("Instance Profile")) {
                    last = e;
                    sleep(LAUNCH_RETRY_DELAY_MS);
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    /** RDS 접속정보 + 프로젝트 환경변수를 EC2 앱이 읽을 env 로 조립한다. */
    private Map<String, String> assembleEnv(Long projectId, int port) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SERVER_PORT", String.valueOf(port));

        databaseRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(db -> db.getStatus() == ProvisionStatus.READY)
                .findFirst()
                .ifPresent(db -> {
                    env.put("SPRING_DATASOURCE_URL", jdbcUrl(db));
                    env.put("SPRING_DATASOURCE_USERNAME", db.getUsername());
                    env.put("SPRING_DATASOURCE_PASSWORD", db.getPassword());
                });

        for (EnvironmentVariable v : environmentVariableRepository.findByProjectIdOrderByScopeAscKeyAsc(projectId)) {
            env.put(v.getKey(), v.getValue());   // 사용자 지정 env 가 우선(뒤에 넣어 덮어씀)
        }
        return env;
    }

    private String jdbcUrl(ProvisionedDatabase db) {
        String scheme = switch (db.getEngine()) {
            case MYSQL -> "mysql";
            case POSTGRESQL -> "postgresql";
        };
        return "jdbc:" + scheme + "://" + db.getHost() + ":" + db.getPort() + "/" + db.getDatabaseName();
    }

    /**
     * 부팅 스크립트. 비밀은 담지 않는다 — S3 에서 jar 를, SSM 경로에서 env 를 인스턴스 IAM 역할로
     * 스스로 당겨온다. Amazon Linux 2023(dnf, aws cli v2 기본 포함).
     */
    private String userDataScript(String bucket, String key, Long projectId, int port) {
        return """
                #!/bin/bash
                set -e
                dnf install -y java-21-amazon-corretto-headless
                mkdir -p /opt/app && cd /opt/app
                aws s3 cp s3://%s/%s /opt/app/app.jar
                while read -r name value; do
                  export "$(basename "$name")=$value"
                done < <(aws ssm get-parameters-by-path --path /qeploy/%d/ --with-decryption --recursive \
                  --query "Parameters[].[Name,Value]" --output text)
                nohup java -jar /opt/app/app.jar --server.port=%d > /var/log/qeploy-app.log 2>&1 &
                """.formatted(bucket, key, projectId, port);
    }

    private ProvisionFailureCode classify(RuntimeException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("UnauthorizedOperation") || m.contains("AccessDenied") || m.contains("not authorized")) {
            return ProvisionFailureCode.IAM_PERMISSION;
        }
        if (m.contains("LimitExceeded") || m.contains("Quota") || m.contains("InstanceLimitExceeded")) {
            return ProvisionFailureCode.QUOTA_EXCEEDED;
        }
        return ProvisionFailureCode.PROVIDER_ERROR;
    }

    private void fail(ProvisionedServer server, ProvisionFailureCode code, String message) {
        server.markFailed(code, message);
        serverRepository.save(server);
        log.warn("EC2 배포 실패: serverId={} code={} 원인={}", server.getId(), code, message);
    }

    private void safeTerminate(CloudConnection connection, String instanceId) {
        try {
            ec2.terminate(connection, instanceId);
        } catch (RuntimeException e) {
            log.error("롤백 terminate 실패(수동 정리 필요): instanceId={} 원인={}", instanceId, e.toString());
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
