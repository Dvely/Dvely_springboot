package com.example.dvely.provisioning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import org.junit.jupiter.api.Test;

/**
 * DOCKER DB EC2 user-data 생성 검증. DB 컨테이너를 띄우고, 준비되면 자기 사설 IP 를 SSM 에 self-report
 * 하는지(컨트롤 플레인이 폴링) 확인한다. 컨테이너 기동+healthcheck 는 로컬 mysql 로 실구동 검증했다.
 */
class DockerDbUserDataTest {

    @Test
    void mysqlUserDataRunsMysqlAndSelfReportsPrivateIp() {
        String s = DockerDbProvisioner.dbUserData(DatabaseEngine.MYSQL, "pw123", 7L, "ap-northeast-2", 3306);

        assertThat(s).startsWith("#!/bin/bash");
        assertThat(s).contains("dnf install -y docker");
        assertThat(s).contains("-p 3306:3306");
        assertThat(s).contains("-e MYSQL_ROOT_PASSWORD='pw123'");
        assertThat(s).contains("-e MYSQL_DATABASE='app'");
        assertThat(s).contains("mysql:8.0");
        assertThat(s).contains("mysqladmin ping");
        // 준비되면 IMDSv2 로 사설 IP 를 얻어 SSM 에 self-report
        assertThat(s).contains("meta-data/local-ipv4");
        assertThat(s).contains("aws ssm put-parameter --region ap-northeast-2 --name \"/qeploy/7/dbstatus/$IID\"");
        assertThat(s).doesNotContain("Caddy");
        assertThat(s).doesNotContain("aws s3 cp");
    }

    @Test
    void postgresUserDataUsesPostgresImageAndReadinessProbe() {
        String s = DockerDbProvisioner.dbUserData(DatabaseEngine.POSTGRESQL, "pw123", 7L, "ap-northeast-2", 5432);

        assertThat(s).contains("-p 5432:5432");
        assertThat(s).contains("-e POSTGRES_PASSWORD='pw123'");
        assertThat(s).contains("-e POSTGRES_DB='app'");
        assertThat(s).contains("postgres:16");
        assertThat(s).contains("pg_isready -U postgres");
        assertThat(s).doesNotContain("mysql");
    }

    @Test
    void statusParamPathIsUnderProjectSsmScope() {
        // DB EC2 인스턴스역할의 SSM Put 스코프(/qeploy/{projectId}/*) 안이어야 한다.
        assertThat(DockerDbProvisioner.statusParam(7L, "i-0abc"))
                .isEqualTo("/qeploy/7/dbstatus/i-0abc");
    }
}
