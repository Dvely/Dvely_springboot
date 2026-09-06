package com.example.dvely.provisioning.infrastructure;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.provisioning.application.port.out.DatabaseProvisioner;
import com.example.dvely.provisioning.application.port.out.ProvisionResult;
import com.example.dvely.provisioning.application.port.out.ProvisionSpec;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner.Ec2InstanceStatus;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner.LaunchSpec;
import java.security.SecureRandom;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 사용자 AWS 계정의 <b>EC2 위 DB 컨테이너</b>를 프로비저닝한다(RDS 대안 — 더 싸고, 앱과 독립된 영속
 * DB). RDS 처럼 <b>비동기</b>다: 승인 핸들러가 {@link #startCreation}으로 EC2 를 띄우고, 상태 워커가
 * {@link #resolveStatus}로 준비를 확인해 접속정보(host)를 채운다.
 *
 * <p><b>준비 판단</b>: DB 는 사설 VPC 안(qeploy-db SG, 3306/5432 를 VPC 내부에서만 허용)에 있어 컨트롤
 * 플레인이 직접 헬스체크할 수 없다. 그래서 DB EC2 가 mysql/postgres 준비 시 자기 사설 IP 를 SSM
 * ({@code /qeploy/{projectId}/dbstatus/{instanceId}})에 self-report 하고, 이 워커가 그 값을 폴링해
 * host 로 삼는다. 백엔드 EC2 는 같은 VPC 사설 IP 로 붙는다(RDS 와 동일한 사설 접속 모델).</p>
 *
 * <p>비밀번호는 이 단일목적 DB 인스턴스의 user-data 에 인라인된다 — 인스턴스엔 DB 컨테이너뿐이고
 * (그 컨테이너가 곧 비번을 가진다) user-data 는 계정 소유자만 읽으므로, 백엔드 EC2 와 달리 별도
 * 노출이 없다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerDbProvisioner implements DatabaseProvisioner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INSTANCE_TYPE = "t3.micro";
    private static final String DB_NAME = "app";
    private static final int LAUNCH_RETRY = 5;
    private static final long LAUNCH_RETRY_DELAY_MS = 3000;

    private final Ec2Provisioner ec2Provisioner;
    private final Ec2InstanceRoleProvisioner roleProvisioner;
    private final SsmParameterStore ssm;

    @Override
    public ProvisionMethod method() {
        return ProvisionMethod.DOCKER;
    }

    @Override
    public ProvisionResult provision(ProvisionSpec spec, String containerId) {
        throw new UnsupportedOperationException("DOCKER DB 는 비동기 생성이라 startCreation 을 사용한다.");
    }

    @Override
    public void deprovision(String resourceId) {
        throw new UnsupportedOperationException("DOCKER DB 삭제는 CloudConnection 이 필요하다. teardown 을 사용한다.");
    }

    /** EC2 인스턴스 식별자와 마스터 자격. host(사설 IP)는 준비 후 self-report 로 채워지므로 여기엔 없다. */
    public record DockerDbCreation(String instanceId, int port, String database, String username, String password) {
        @Override
        public String toString() {
            return "DockerDbCreation[instanceId=" + instanceId + ", port=" + port
                    + ", database=" + database + ", username=" + username + ", password=***]";
        }
    }

    /** 준비 상태 스냅샷. host 는 DB EC2 가 self-report 하기 전에는 null. terminated 면 실패로 본다. */
    public record DockerDbStatus(String ec2State, String host) {}

    /**
     * DB EC2 생성을 시작한다(즉시 반환). SG·인스턴스역할(SSM self-report 권한)·AMI 를 준비하고, DB
     * 컨테이너를 띄우는 user-data 로 인스턴스를 띄운다. 준비 확인은 상태 워커가 한다.
     */
    public DockerDbCreation startCreation(CloudConnection connection, DatabaseEngine engine, Long projectId) {
        String password = randomPassword();
        String username = username(engine);
        int port = port(engine);
        String sgId = ec2Provisioner.ensureDatabaseSecurityGroup(connection);
        String profileName = roleProvisioner.ensureDbWriterInstanceProfile(connection, projectId);
        String ami = ssm.latestAmazonLinux2023Ami(connection);
        String userData = dbUserData(engine, password, projectId, connection.getRegion(), port);
        String instanceId = launchWithRetry(connection,
                new LaunchSpec(INSTANCE_TYPE, ami, userData, sgId, null, profileName, "qeploy-db-" + projectId));
        log.info("DOCKER DB EC2 생성 시작: instanceId={} engine={} projectId={}", instanceId, engine, projectId);
        return new DockerDbCreation(instanceId, port, DB_NAME, username, password);
    }

    /** EC2 상태 + self-report 된 사설 IP 를 조회한다. host 가 있으면 준비 완료. */
    public DockerDbStatus resolveStatus(CloudConnection connection, Long projectId, String instanceId) {
        Ec2InstanceStatus ec2 = ec2Provisioner.describe(connection, instanceId);
        String host = ssm.getParameterQuietly(connection, statusParam(projectId, instanceId)).orElse(null);
        return new DockerDbStatus(ec2.state(), host);
    }

    /** DB 를 정리한다 — EC2 종료 + self-report 파라미터 삭제(best-effort). */
    public void teardown(CloudConnection connection, Long projectId, String instanceId) {
        ec2Provisioner.terminate(connection, instanceId);
        ssm.deleteParameterQuietly(connection, statusParam(projectId, instanceId));
        log.info("DOCKER DB EC2 종료: instanceId={} projectId={}", instanceId, projectId);
    }

    /** 준비 완료 후 이 파라미터는 지운다 — 같은 프로젝트 경로라 백엔드 SSM 풀에 섞이지 않게. */
    public void clearReadySignal(CloudConnection connection, Long projectId, String instanceId) {
        ssm.deleteParameterQuietly(connection, statusParam(projectId, instanceId));
    }

    /** self-report 파라미터 이름. DB EC2 의 인스턴스역할 SSM Put 스코프(/qeploy/{projectId}/*) 안이다. */
    public static String statusParam(Long projectId, String instanceId) {
        return "/qeploy/" + projectId + "/dbstatus/" + instanceId;
    }

    /**
     * DB EC2 user-data. DB 컨테이너를 띄우고, 준비되면 자기 사설 IP 를 SSM 에 self-report 한다. 비밀은
     * 이 단일목적 인스턴스에 인라인(위 클래스 주석 참조). S3·Caddy 없음(백엔드 서버와 다른 최소 셋).
     */
    static String dbUserData(DatabaseEngine engine, String password, Long projectId, String region, int port) {
        String run = switch (engine) {
            case MYSQL -> """
                    docker run -d --name qeploy-db --restart unless-stopped -p %d:3306 \
                      -e MYSQL_ROOT_PASSWORD='%s' -e MYSQL_DATABASE='%s' \
                      -v qeploy-dbdata:/var/lib/mysql mysql:8.0
                    for i in $(seq 1 120); do
                      docker exec qeploy-db mysqladmin ping -h 127.0.0.1 -uroot -p'%s' >/dev/null 2>&1 && break
                      sleep 5
                    done
                    """.formatted(port, password, DB_NAME, password);
            case POSTGRESQL -> """
                    docker run -d --name qeploy-db --restart unless-stopped -p %d:5432 \
                      -e POSTGRES_PASSWORD='%s' -e POSTGRES_DB='%s' \
                      -v qeploy-dbdata:/var/lib/postgresql/data postgres:16
                    for i in $(seq 1 120); do
                      docker exec qeploy-db pg_isready -U postgres >/dev/null 2>&1 && break
                      sleep 5
                    done
                    """.formatted(port, password, DB_NAME);
        };
        return """
                #!/bin/bash
                set -e
                dnf install -y docker
                systemctl enable --now docker
                %s
                TOKEN=$(curl -sX PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 120")
                IID=$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-id)
                PIP=$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/local-ipv4)
                aws ssm put-parameter --region %s --name "/qeploy/%d/dbstatus/$IID" --type String --value "$PIP" --overwrite
                """.formatted(run, region, projectId);
    }

    private String launchWithRetry(CloudConnection connection, LaunchSpec spec) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= LAUNCH_RETRY; attempt++) {
            try {
                return ec2Provisioner.launch(connection, spec);
            } catch (RuntimeException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (attempt < LAUNCH_RETRY && msg.contains("Instance Profile")) {
                    last = e;   // 방금 만든 IAM 프로파일 전파 대기(최종적 일관성)
                    sleep(LAUNCH_RETRY_DELAY_MS);
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    private String username(DatabaseEngine engine) {
        return switch (engine) {
            case MYSQL -> "root";
            case POSTGRESQL -> "postgres";
        };
    }

    private int port(DatabaseEngine engine) {
        return switch (engine) {
            case MYSQL -> 3306;
            case POSTGRESQL -> 5432;
        };
    }

    private String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
