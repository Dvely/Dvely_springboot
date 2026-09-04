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
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.infrastructure.Ec2InstanceRoleProvisioner;
import com.example.dvely.provisioning.application.port.out.FrontendOriginPort;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner.LaunchSpec;
import com.example.dvely.provisioning.infrastructure.config.Ec2ProvisioningProperties;
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
    private final DockerImageBuildService imageBuildService;
    private final S3ArtifactStore s3;
    private final SsmParameterStore ssm;
    private final Ec2InstanceRoleProvisioner roleProvisioner;
    private final Ec2Provisioner ec2;
    private final ProvisionedDatabaseRepository databaseRepository;
    private final EnvironmentVariableRepository environmentVariableRepository;
    private final FrontendOriginPort frontendOriginPort;
    private final Ec2ProvisioningProperties ec2Properties;

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
        boolean docker = server.getDeployMode() == ServerDeployMode.DOCKER;
        Path artifact = null;
        String instanceId = null;
        String eipAllocationId = null;
        try {
            // 배포 형태로 산출물이 갈린다: NATIVE=jar, DOCKER=이미지 tar. 나머지(S3 업로드·SSM·launch)는 공통.
            artifact = docker
                    ? imageBuildService.buildImageTar(ownerUserId, projectId)
                    : buildService.buildJar(ownerUserId, projectId);

            String bucket = s3.bucketNameFor(connection);
            String key = docker ? s3.imageKeyFor(projectId) : s3.jarKeyFor(projectId);
            s3.ensureBucket(connection, bucket);
            s3.uploadJar(connection, bucket, key, artifact);   // generic: Path→key 멀티파트 업로드

            ssm.putAll(connection, projectId, assembleEnv(projectId, server.getPort()));

            // IAM 역할 생성이 금지된 환경(AWS Academy Learner Lab 등)에서는 미리 존재하는
            // 프로파일(예: LabInstanceProfile)을 설정으로 지정해 생성을 건너뛴다. 기본은 자동 생성.
            String profileName;
            if (ec2Properties.hasInstanceProfileOverride()) {
                profileName = ec2Properties.instanceProfileOverride();
                log.info("[BackendDeploy] 인스턴스 프로파일 오버라이드 사용(생성 건너뜀): {}", profileName);
            } else {
                profileName = roleProvisioner.ensureInstanceProfile(connection, projectId, bucket);
            }
            String sgId = ec2.ensureSecurityGroup(connection, server.getPort());
            String ami = ssm.latestAmazonLinux2023Ami(connection);
            String userData = docker
                    ? dockerUserDataScript(bucket, key, projectId,
                            DockerImageBuildService.imageTagFor(projectId), server.getPort(),
                            ec2Properties.tlsAskBaseUrlOrEmpty())
                    : userDataScript(bucket, key, projectId, server.getPort(),
                            ec2Properties.tlsAskBaseUrlOrEmpty());

            LaunchSpec spec = new LaunchSpec(server.getInstanceType(), ami, userData, sgId, null,
                    profileName, "qeploy-backend-" + projectId);
            instanceId = launchWithRetry(connection, spec);

            // 안정 주소(EIP) 연결 — 자동할당 public IP 는 stop·재배포마다 바뀌어 도메인이 깨진다.
            // 종료 시 release 는 terminate 정리가 담당한다(server.elasticIpAllocationId 로).
            Ec2Provisioner.ElasticIp eip = ec2.allocateAndAssociateElasticIp(
                    connection, instanceId, "qeploy-backend-" + projectId);
            eipAllocationId = eip.allocationId();
            server.assignElasticIp(eipAllocationId);

            server.beginProvisioning(instanceId);
            serverRepository.save(server);
            log.info("EC2 배포 시작 완료: serverId={} instanceId={} projectId={}",
                    server.getId(), instanceId, projectId);
        } catch (BackendBuildException e) {
            fail(server, ProvisionFailureCode.PROVIDER_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            // launch 이후(인스턴스 생김) 실패면 과금이라 즉시 롤백한다. EIP 도 붙었으면 release
            // (연결만 풀려도 할당은 남아 계속 과금).
            if (instanceId != null) {
                safeTerminate(connection, instanceId);
            }
            if (eipAllocationId != null) {
                safeReleaseEip(connection, eipAllocationId);
            }
            fail(server, classify(e), e.getMessage());
        } finally {
            if (artifact != null) {
                try { Files.deleteIfExists(artifact); } catch (IOException ignored) { }
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

        // 프론트↔백엔드 CORS: 프로젝트 프론트 오리진을 넘겨준다. 백엔드(템플릿/사용자 앱)가 이 값을
        // 읽어 CORS 허용 오리진으로 쓴다 — 없으면 프론트가 백엔드 도메인을 호출할 때 브라우저가 막는다.
        java.util.List<String> origins = frontendOriginPort.resolveAllowedOrigins(projectId);
        if (!origins.isEmpty()) {
            env.put("QEPLOY_ALLOWED_ORIGINS", String.join(",", origins));
        }

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
    // 앱은 8080(localhost). HTTPS 는 Caddy 리버스프록시가 on-demand TLS 로 종단한다(도메인은 배포
    // 후에 붙고 인스턴스에 SSH 가 없어 재설정을 못 하므로 on-demand). 남용 방지 ask 는 자기완결적 —
    // *.qeploy.com 만 인증서 발급 허용(우리가 qeploy.com DNS 를 통제하므로 남의 도메인은 우리 IP 로
    // 오지 못한다). Caddy·ask 설치 실패해도 앱은 이미 떠 있어 8080 헬스체크는 통과한다(HTTPS 만 없다).
    //
    // NATIVE 모드: jar 를 받아 java -jar. DOCKER 모드는 dockerUserDataScript(이미지 load 후 run). HTTPS
    // 종단(Caddy·ask)은 두 모드 공통(httpsSection) — Caddy 는 localhost:port 로 프록시하므로 실행 형태와
    // 무관하다.
    static String userDataScript(String bucket, String key, Long projectId, int port, String tlsAskBase) {
        return """
                #!/bin/bash
                set -e
                dnf install -y java-21-amazon-corretto-headless python3
                mkdir -p /opt/app && cd /opt/app
                aws s3 cp s3://%s/%s /opt/app/app.jar
                while read -r name value; do
                  export "$(basename "$name")=$value"
                done < <(aws ssm get-parameters-by-path --path /qeploy/%d/ --with-decryption --recursive \
                  --query "Parameters[].[Name,Value]" --output text)
                nohup java -jar /opt/app/app.jar --server.port=%d > /var/log/qeploy-app.log 2>&1 &
                """.formatted(bucket, key, projectId, port)
                + httpsSection(port, tlsAskBase);
    }

    /**
     * DOCKER 모드 user-data. 이미지 tar 를 S3 에서 받아 {@code docker load} 후 {@code run} 한다. 앱은
     * 컨테이너 안에서 SERVER_PORT(SSM env)로 리슨하고 호스트 port 로 매핑 — Caddy 는 native 와 똑같이
     * localhost:port 로 프록시한다(HTTPS 공통). 비밀·env 는 SSM 에서 env 파일로 내려 {@code --env-file}
     * 로 컨테이너에만 주입한다(호스트 셸에 export 하지 않는다).
     */
    static String dockerUserDataScript(String bucket, String key, Long projectId, String imageTag,
                                       int port, String tlsAskBase) {
        return """
                #!/bin/bash
                set -e
                dnf install -y python3 docker
                systemctl enable --now docker
                mkdir -p /opt/app && cd /opt/app
                aws s3 cp s3://%s/%s /opt/app/image.tar
                docker load -i /opt/app/image.tar
                aws ssm get-parameters-by-path --path /qeploy/%d/ --with-decryption --recursive \
                  --query "Parameters[].[Name,Value]" --output text | while read -r name value; do
                    echo "$(basename "$name")=$value"
                  done > /opt/app/app.env
                docker run -d --restart unless-stopped -p %d:%d --env-file /opt/app/app.env %s
                """.formatted(bucket, key, projectId, port, port, imageTag)
                + httpsSection(port, tlsAskBase);
    }

    /** HTTPS 종단(Caddy on-demand + 남용 방지 ask). NATIVE·DOCKER 공통 — localhost:port 로 프록시. */
    static String httpsSection(int port, String tlsAskBase) {
        return """

                set +e
                cat > /opt/tls-ask.py <<'PYEOF'
                import http.server, urllib.parse, urllib.request
                ASK_BASE = "%s"
                class H(http.server.BaseHTTPRequestHandler):
                    def do_GET(self):
                        q = urllib.parse.urlparse(self.path).query
                        d = urllib.parse.parse_qs(q).get('domain', [''])[0].lower()
                        if d.endswith('.qeploy.com'):
                            ok = True
                        elif ASK_BASE:
                            try:
                                ok = urllib.request.urlopen(ASK_BASE + '/api/v1/tls/allow?domain=' + d, timeout=5).status == 200
                            except Exception:
                                ok = False
                        else:
                            ok = False
                        self.send_response(200 if ok else 403)
                        self.end_headers()
                    def log_message(self, *a): pass
                http.server.HTTPServer(('127.0.0.1', 9000), H).serve_forever()
                PYEOF
                nohup python3 /opt/tls-ask.py > /var/log/qeploy-tls-ask.log 2>&1 &

                curl -sL "https://caddyserver.com/api/download?os=linux&arch=amd64" -o /usr/bin/caddy
                chmod +x /usr/bin/caddy
                cat > /opt/Caddyfile <<CADDYEOF
                {
                    email admin@qeploy.com
                    on_demand_tls {
                        ask http://127.0.0.1:9000
                    }
                }
                *.qeploy.com {
                    reverse_proxy 127.0.0.1:%d
                    tls {
                        on_demand
                    }
                }
                CADDYEOF
                nohup /usr/bin/caddy run --config /opt/Caddyfile --adapter caddyfile > /var/log/qeploy-caddy.log 2>&1 &
                """.formatted(tlsAskBase, port);
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

    private void safeReleaseEip(CloudConnection connection, String allocationId) {
        try {
            ec2.releaseElasticIp(connection, allocationId);
        } catch (RuntimeException e) {
            log.error("롤백 EIP release 실패(수동 정리 필요, 유휴 EIP 과금 주의): allocationId={} 원인={}",
                    allocationId, e.toString());
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
