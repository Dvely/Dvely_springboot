package com.example.dvely.provisioning.application.service;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.environment.domain.model.EnvironmentVariable;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ProvisionFailureCode;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.infrastructure.Ec2InstanceRoleProvisioner;
import com.example.dvely.provisioning.application.port.out.FrontendOriginPort;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner.LaunchSpec;
import com.example.dvely.provisioning.infrastructure.EcrImageRegistry;
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
    private final WebImageBuildService webImageBuildService;
    private final EcrImageRegistry ecr;
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
        // DOCKER 모드의 이미지 전달: 기본 S3(docker save→S3→load), 설정으로 켜면 ECR(build→push→pull).
        // NATIVE(jar)는 항상 S3. useEcr 는 이후 IAM 권한·정리 방식까지 가른다.
        boolean useEcr = docker && ec2Properties.useEcr();
        // 번들 DB·웹 컨테이너: DOCKER 배포에서 같은 EC2 에 DB·프론트 컨테이너를 docker compose 로 함께
        // 띄운다(submit 이 DOCKER 강제). 둘 중 하나라도 있으면 단일 docker run 대신 compose 를 쓴다.
        DatabaseEngine bundledDb = server.getBundledDbEngine();
        boolean web = server.hasWebFrontend();
        String frontendRepo = server.getFrontendRepo();
        String frontendDir = server.getFrontendDir();
        String apiPrefix = server.getApiPathPrefix();
        String tlsAsk = ec2Properties.tlsAskBaseUrlOrEmpty();
        int port = server.getPort();
        boolean compose = bundledDb != null || web;
        Path artifact = null;
        String instanceId = null;
        String eipAllocationId = null;
        try {
            String bucket = s3.bucketNameFor(connection);
            String userData;
            if (useEcr) {
                // ECR 전달: S3 를 거치지 않고 컨트롤 플레인이 이미지를 ECR 로 push, EC2 가 pull.
                ecr.ensureRepository(connection, projectId);
                EcrImageRegistry.EcrAuth auth = ecr.authorize(connection);
                String appRef = ecr.imageRefFor(connection, projectId);
                imageBuildService.buildAndPushImage(ownerUserId, projectId, auth, appRef);
                String webRef = null;
                if (web) {
                    ecr.ensureWebRepository(connection, projectId);
                    webRef = ecr.webImageRefFor(connection, projectId);
                    webImageBuildService.buildAndPushWebImage(ownerUserId, projectId,
                            frontendRepo, frontendDir, apiPrefix, auth, webRef);   // 토큰은 레지스트리 공용, 재사용
                }
                userData = compose
                        ? ecrComposeUserDataScript(connection.getRegion(), auth.registry(), appRef, webRef,
                                bundledDb, projectId, port, tlsAsk)
                        : ecrUserDataScript(connection.getRegion(), auth.registry(), appRef,
                                projectId, port, tlsAsk);
            } else {
                // S3 전달: NATIVE=jar, DOCKER=이미지 tar. 산출물을 S3 로 올리고 EC2 가 인스턴스 역할로 받는다.
                artifact = docker
                        ? imageBuildService.buildImageTar(ownerUserId, projectId)
                        : buildService.buildJar(ownerUserId, projectId);
                String key = docker ? s3.imageKeyFor(projectId) : s3.jarKeyFor(projectId);
                s3.ensureBucket(connection, bucket);
                s3.uploadJar(connection, bucket, key, artifact);   // generic: Path→key 멀티파트 업로드
                String webKey = null;
                if (docker && web) {
                    webKey = s3.webImageKeyFor(projectId);
                    Path webArtifact = webImageBuildService.buildWebImageTar(
                            ownerUserId, projectId, frontendRepo, frontendDir, apiPrefix);
                    try {
                        s3.uploadJar(connection, bucket, webKey, webArtifact);
                    } finally {
                        try { Files.deleteIfExists(webArtifact); } catch (IOException ignored) { }
                    }
                }
                if (docker) {
                    String appImageTag = DockerImageBuildService.imageTagFor(projectId);
                    String webImageTag = web ? WebImageBuildService.webImageTagFor(projectId) : null;
                    userData = compose
                            ? dockerComposeUserDataScript(bucket, key, appImageTag, webKey, webImageTag,
                                    bundledDb, projectId, port, tlsAsk)
                            : dockerUserDataScript(bucket, key, projectId, appImageTag, port, tlsAsk);
                } else {
                    userData = userDataScript(bucket, key, projectId, port, tlsAsk);
                }
            }

            ssm.putAll(connection, projectId, assembleEnv(server));

            // IAM 역할 생성이 금지된 환경(AWS Academy Learner Lab 등)에서는 미리 존재하는
            // 프로파일(예: LabInstanceProfile)을 설정으로 지정해 생성을 건너뛴다. 기본은 자동 생성.
            // ECR 전달이면 인스턴스 역할에 ECR pull 권한을 더한다(useEcr).
            String profileName;
            if (ec2Properties.hasInstanceProfileOverride()) {
                profileName = ec2Properties.instanceProfileOverride();
                log.info("[BackendDeploy] 인스턴스 프로파일 오버라이드 사용(생성 건너뜀): {}", profileName);
            } else {
                profileName = roleProvisioner.ensureInstanceProfile(connection, projectId, bucket, useEcr);
            }
            String sgId = ec2.ensureSecurityGroup(connection, server.getPort());
            String ami = ssm.latestAmazonLinux2023Ami(connection);

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

    /** DB 접속정보(RDS 또는 번들) + 프로젝트 환경변수를 EC2 앱이 읽을 env 로 조립한다. */
    private Map<String, String> assembleEnv(ProvisionedServer server) {
        Long projectId = server.getProjectId();
        Map<String, String> env = new LinkedHashMap<>();
        // 리슨 포트를 두 관례로 다 준다: SERVER_PORT(Spring relaxed-binding: server.port)와
        // PORT(Node/Next 관례). 스택에 따라 앱이 둘 중 무엇을 읽든 host 매핑 포트와 맞는다.
        env.put("SERVER_PORT", String.valueOf(server.getPort()));
        env.put("PORT", String.valueOf(server.getPort()));

        if (server.hasBundledDb()) {
            // 번들 DB: 같은 EC2 의 db 컨테이너로 배선한다. 비밀번호를 여기서 한 번 생성해 앱
            // (SPRING_DATASOURCE_PASSWORD)과 db 서비스(compose 가 .env 의 DB_PASSWORD 를 치환)가 같은
            // 값을 쓰게 한다. 비밀은 SSM 으로만 가고(호스트에서 .env 로 내려짐) user-data 엔 안 담긴다.
            DatabaseEngine engine = server.getBundledDbEngine();
            String password = randomPassword();
            env.put("DB_NAME", BUNDLED_DB_NAME);
            env.put("DB_PASSWORD", password);
            env.put("SPRING_DATASOURCE_URL", bundledJdbcUrl(engine));
            env.put("SPRING_DATASOURCE_USERNAME", bundledDbUsername(engine));
            env.put("SPRING_DATASOURCE_PASSWORD", password);
        } else {
            databaseRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                    .filter(db -> db.getStatus() == ProvisionStatus.READY)
                    .findFirst()
                    .ifPresent(db -> {
                        env.put("SPRING_DATASOURCE_URL", jdbcUrl(db));
                        env.put("SPRING_DATASOURCE_USERNAME", db.getUsername());
                        env.put("SPRING_DATASOURCE_PASSWORD", db.getPassword());
                    });
        }

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

    /** 번들 DB 이름(고정). compose 의 DB 초기화(MYSQL_DATABASE/POSTGRES_DB)와 앱 JDBC URL 이 이걸 쓴다. */
    private static final String BUNDLED_DB_NAME = "appdb";

    /** 번들 DB JDBC URL — 같은 compose 네트워크의 db 서비스명으로 접속한다. */
    private static String bundledJdbcUrl(DatabaseEngine engine) {
        return switch (engine) {
            case MYSQL -> "jdbc:mysql://db:3306/" + BUNDLED_DB_NAME;
            case POSTGRESQL -> "jdbc:postgresql://db:5432/" + BUNDLED_DB_NAME;
        };
    }

    /** 번들 DB 접속 계정 — 공식 이미지의 슈퍼유저(단일 테넌트, 같은 인스턴스 안). */
    private static String bundledDbUsername(DatabaseEngine engine) {
        return switch (engine) {
            case MYSQL -> "root";
            case POSTGRESQL -> "postgres";
        };
    }

    /** 번들 DB 비밀번호 — 셸·URL·DB 에서 안전한 영숫자만(특수문자 회피). */
    private static String randomPassword() {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
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

    /**
     * ECR 전달 user-data. S3 대신 ECR 에서 이미지를 받는다 — 인스턴스 역할로 {@code aws ecr
     * get-login-password} 해 {@code docker login} 후 {@code docker pull}. 이후는 DOCKER-S3 와 동일
     * ({@code --env-file} 로 SSM env 주입, host 포트 매핑, HTTPS 공통). imageRef 는 {registry}/{repo}:latest.
     */
    static String ecrUserDataScript(String region, String registry, String imageRef, Long projectId,
                                    int port, String tlsAskBase) {
        return """
                #!/bin/bash
                set -e
                dnf install -y python3 docker
                systemctl enable --now docker
                mkdir -p /opt/app && cd /opt/app
                aws ecr get-login-password --region %s | docker login --username AWS --password-stdin %s
                docker pull %s
                aws ssm get-parameters-by-path --path /qeploy/%d/ --with-decryption --recursive \
                  --query "Parameters[].[Name,Value]" --output text | while read -r name value; do
                    echo "$(basename "$name")=$value"
                  done > /opt/app/app.env
                docker run -d --restart unless-stopped -p %d:%d --env-file /opt/app/app.env %s
                """.formatted(region, registry, imageRef, projectId, port, port, imageRef)
                + httpsSection(port, tlsAskBase);
    }

    /**
     * compose(S3 전달) user-data. 앱[+웹] 이미지는 S3 에서 {@code docker load}, DB(번들 시)·웹(있으면)은
     * compose 로 같은 EC2 에 함께 띄운다. 비밀·접속정보는 SSM 에서 {@code .env}(compose 작업 디렉터리)로
     * 내려 앱 env 와 db 치환에 함께 쓴다 — user-data 엔 비밀을 담지 않는다. webKey/webImageTag 가 null 이면 웹 없음.
     */
    static String dockerComposeUserDataScript(String bucket, String appKey, String appImageTag,
                                              String webKey, String webImageTag, DatabaseEngine dbEngine,
                                              Long projectId, int port, String tlsAskBase) {
        return """
                #!/bin/bash
                set -e
                dnf install -y python3 docker
                systemctl enable --now docker
                mkdir -p /opt/app && cd /opt/app
                aws s3 cp s3://%s/%s /opt/app/image.tar
                docker load -i /opt/app/image.tar
                """.formatted(bucket, appKey)
                + s3LoadWebSection(bucket, webKey)
                + ssmToEnvFileSection(projectId)
                + composeUpSection(appImageTag, webImageTag, dbEngine, port)
                + httpsSection(port, tlsAskBase);
    }

    /** compose(ECR 전달) user-data. 앱[+웹] 이미지는 ECR 에서 pull, 나머지는 S3 버전과 동일. webRef null 이면 웹 없음. */
    static String ecrComposeUserDataScript(String region, String registry, String appRef, String webRef,
                                           DatabaseEngine dbEngine, Long projectId, int port, String tlsAskBase) {
        return """
                #!/bin/bash
                set -e
                dnf install -y python3 docker
                systemctl enable --now docker
                mkdir -p /opt/app && cd /opt/app
                aws ecr get-login-password --region %s | docker login --username AWS --password-stdin %s
                docker pull %s
                """.formatted(region, registry, appRef)
                + ecrPullWebSection(webRef)
                + ssmToEnvFileSection(projectId)
                + composeUpSection(appRef, webRef, dbEngine, port)
                + httpsSection(port, tlsAskBase);
    }

    /** 웹 이미지도 S3 에서 load(webKey 있으면). 앱 load 뒤에 붙는다. */
    private static String s3LoadWebSection(String bucket, String webKey) {
        if (webKey == null) {
            return "";
        }
        return """
                aws s3 cp s3://%s/%s /opt/app/web.tar
                docker load -i /opt/app/web.tar
                """.formatted(bucket, webKey);
    }

    /** 웹 이미지도 ECR 에서 pull(webRef 있으면). 로그인은 앱 pull 때 이미 됐다. */
    private static String ecrPullWebSection(String webRef) {
        return webRef == null ? "" : "docker pull " + webRef + "\n";
    }

    /** SSM 파라미터를 compose 작업 디렉터리의 {@code .env} 로 내린다(앱 env_file + compose 변수 치환 겸용). */
    private static String ssmToEnvFileSection(Long projectId) {
        return """
                aws ssm get-parameters-by-path --path /qeploy/%d/ --with-decryption --recursive \
                  --query "Parameters[].[Name,Value]" --output text | while read -r name value; do
                    echo "$(basename "$name")=$value"
                  done > /opt/app/.env
                """.formatted(projectId);
    }

    /**
     * docker compose 플러그인을 확보하고 compose.yml 을 써서 {@code up} 한다. AL2023 기본에 compose 플러그인이
     * 없을 수 있어 릴리스 바이너리를 내려받는다(EC2 는 인터넷 egress 가 있다 — Caddy 도 같은 방식). 이미지는
     * 이미 로컬(load/pull)에 있으므로 compose 가 그대로 쓴다.
     */
    private static String composeUpSection(String appImageRef, String webImageRef, DatabaseEngine dbEngine, int port) {
        return """
                mkdir -p /usr/libexec/docker/cli-plugins
                curl -sSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64" \
                  -o /usr/libexec/docker/cli-plugins/docker-compose
                chmod +x /usr/libexec/docker/cli-plugins/docker-compose
                cat > /opt/app/compose.yml <<'YAML'
                %sYAML
                docker compose -f /opt/app/compose.yml --project-directory /opt/app up -d
                """.formatted(composeFile(appImageRef, webImageRef, dbEngine, port));
    }

    /**
     * compose.yml 내용. 조합에 따라 서비스를 조립한다: db(번들 시, 엔진별 이미지+볼륨+헬스체크) · app(항상,
     * 이미지+.env) · web(있으면, nginx 이미지). 웹이 있으면 <b>web 이 호스트 포트를 차지하고 app 은 내부
     * 전용</b>(nginx 가 app:8080 으로 프록시) — 없으면 app 이 호스트 포트. 비밀은 없다(.env 치환).
     */
    private static String composeFile(String appImageRef, String webImageRef, DatabaseEngine dbEngine, int port) {
        boolean hasDb = dbEngine != null;
        boolean hasWeb = webImageRef != null;
        StringBuilder sb = new StringBuilder("services:\n");
        if (hasDb) {
            sb.append(dbServiceYaml(dbEngine));
        }
        sb.append("  app:\n");
        sb.append("    image: ").append(appImageRef).append('\n');
        if (hasDb) {
            sb.append("    depends_on:\n      db:\n        condition: service_healthy\n");
        }
        sb.append("    env_file:\n      - .env\n");
        if (!hasWeb) {   // 웹이 없을 때만 app 이 호스트 포트를 연다(웹이 있으면 nginx 가 연다)
            sb.append("    ports:\n      - \"").append(port).append(':').append(port).append("\"\n");
        }
        sb.append("    restart: unless-stopped\n");
        if (hasWeb) {
            sb.append("  web:\n");
            sb.append("    image: ").append(webImageRef).append('\n');
            sb.append("    depends_on:\n      - app\n");
            sb.append("    ports:\n      - \"").append(port).append(":80\"\n");
            sb.append("    restart: unless-stopped\n");
        }
        if (hasDb) {
            sb.append("volumes:\n  dbdata:\n");
        }
        return sb.toString();
    }

    /** 번들 DB 서비스 YAML(2칸 들여쓰기, services 아래). 비밀은 .env 의 ${DB_PASSWORD}/${DB_NAME} 치환. */
    private static String dbServiceYaml(DatabaseEngine dbEngine) {
        return switch (dbEngine) {
            case MYSQL -> """
                      db:
                        image: mysql:8.0
                        environment:
                          MYSQL_ROOT_PASSWORD: ${DB_PASSWORD}
                          MYSQL_DATABASE: ${DB_NAME}
                        volumes:
                          - dbdata:/var/lib/mysql
                        healthcheck:
                          test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-uroot", "-p${DB_PASSWORD}"]
                          interval: 5s
                          timeout: 5s
                          retries: 30
                    """;
            case POSTGRESQL -> """
                      db:
                        image: postgres:16
                        environment:
                          POSTGRES_PASSWORD: ${DB_PASSWORD}
                          POSTGRES_DB: ${DB_NAME}
                        volumes:
                          - dbdata:/var/lib/postgresql/data
                        healthcheck:
                          test: ["CMD-SHELL", "pg_isready -U postgres"]
                          interval: 5s
                          timeout: 5s
                          retries: 30
                    """;
        };
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
