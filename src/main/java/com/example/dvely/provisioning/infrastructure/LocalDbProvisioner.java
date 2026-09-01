package com.example.dvely.provisioning.infrastructure;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.provisioning.application.port.out.DatabaseProvisioner;
import com.example.dvely.provisioning.application.port.out.ProvisionResult;
import com.example.dvely.provisioning.application.port.out.ProvisionSpec;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import java.security.SecureRandom;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 프리뷰 세션의 세션 전용 네트워크에 DB 컨테이너를 형제로 띄운다. 테스트용이라 사용자 AWS 자격도
 * 과금도 없다 — 세션이 만료되면 컨테이너·네트워크가 함께 회수된다.
 *
 * 엔진별 이미지·환경변수·준비 핑은 2026-09-01 로컬 Docker 실측으로 확정했다: postgres 는
 * pg_isready, mysql 은 mysqladmin ping 으로 "접속을 받는 시점"을 기다린다. 컨테이너가 뜬 것만으로
 * READY 로 보면 첫 접속이 거부되므로, 실제 핑이 통과할 때까지 기다린 것이 이 값의 핵심이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalDbProvisioner implements DatabaseProvisioner {

    private final DockerContainerService dockerService;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALIAS = "db";      // 앱이 이 이름으로 접속한다
    private static final String DB_NAME = "app";
    private static final String DB_USER = "app";

    @Override
    public ProvisionMethod method() {
        return ProvisionMethod.LOCAL;
    }

    @Override
    public ProvisionResult provision(ProvisionSpec spec, String containerId) {
        String sessionId = containerId;   // 세션 컨테이너 ID 를 네트워크 식별자로 쓴다
        String network = dockerService.createSessionNetwork(sessionId);
        String password = randomPassword();
        int port = port(spec.engine());

        String dbContainerId;
        try {
            dbContainerId = dockerService.createDatabaseContainer(
                    network, ALIAS, image(spec.engine()),
                    env(spec.engine(), password), readyProbe(spec.engine()));
        } catch (RuntimeException e) {
            // 컨테이너 준비 실패 시 네트워크가 남지 않도록 정리하고 다시 던진다.
            // (createDatabaseContainer 는 준비 실패한 컨테이너를 스스로 제거한다.)
            dockerService.removeSessionNetwork(network);
            throw e;
        }

        try {
            // 앱(프리뷰 컨테이너)을 같은 세션 네트워크에 붙여야 ALIAS("db")로 접속할 수 있다.
            // 이게 없으면 DB 가 READY 여도 앱은 접속하지 못한다.
            dockerService.connectContainerToNetwork(network, sessionId);
        } catch (RuntimeException e) {
            // 연결 실패면 방금 만든 DB 컨테이너와 네트워크를 통째로 회수하고 던진다.
            dockerService.removeDatabaseContainerWithNetwork(dbContainerId);
            throw e;
        }

        return new ProvisionResult(dbContainerId, ALIAS, port, DB_NAME, DB_USER, password);
    }

    @Override
    public void deprovision(String resourceId) {
        // resourceId = DB 컨테이너 ID. 컨테이너와 그 세션 전용 네트워크를 함께 정리한다.
        dockerService.removeDatabaseContainerWithNetwork(resourceId);
    }

    private String image(DatabaseEngine engine) {
        return switch (engine) {
            case POSTGRESQL -> "postgres:16-alpine";
            case MYSQL -> "mysql:8.4";
        };
    }

    private int port(DatabaseEngine engine) {
        return switch (engine) {
            case POSTGRESQL -> 5432;
            case MYSQL -> 3306;
        };
    }

    private List<String> env(DatabaseEngine engine, String password) {
        return switch (engine) {
            case POSTGRESQL -> List.of(
                    "POSTGRES_DB=" + DB_NAME, "POSTGRES_USER=" + DB_USER, "POSTGRES_PASSWORD=" + password);
            case MYSQL -> List.of(
                    "MYSQL_DATABASE=" + DB_NAME, "MYSQL_USER=" + DB_USER, "MYSQL_PASSWORD=" + password,
                    // mysql 은 root 비밀번호가 필수다. 앱은 app 계정으로만 붙으므로 root 는 컨테이너
                    // 안에서만 쓰이고 밖으로 나가지 않는다.
                    "MYSQL_ROOT_PASSWORD=" + randomPassword());
        };
    }

    private List<String> readyProbe(DatabaseEngine engine) {
        return switch (engine) {
            case POSTGRESQL -> List.of("pg_isready", "-U", DB_USER, "-d", DB_NAME);
            // 비밀번호를 명령줄 리터럴로 넣으면 exec 이 debug 로그·인터럽트 예외에 명령 전문을
            // 남길 때 함께 샌다. 대신 컨테이너에 이미 있는 MYSQL_PASSWORD 를 셸에서 확장하게 둔다 —
            // 확장은 컨테이너 안에서 일어나고, 우리가 보는 명령 문자열에는 "$MYSQL_PASSWORD" 만 남는다.
            case MYSQL -> List.of("mysqladmin", "ping", "-h", "localhost",
                    "-u", DB_USER, "-p\"$MYSQL_PASSWORD\"");
        };
    }

    private String randomPassword() {
        // 컨테이너 환경변수로만 흐르는 값이라 URL/셸 특수문자 없는 영숫자로 둔다.
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return sb.toString();
    }
}
