package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import org.junit.jupiter.api.Test;

/**
 * 번들 DB(같은 EC2 에 앱+DB 컨테이너 compose) user-data 생성 검증. 여기서 못박는 compose.yml 형태는
 * 로컬에서 실제 {@code docker compose up} + JPA {@code /db} 200 으로 구동 검증한 것과 동일하다 —
 * Java 텍스트블록이 그 형태(YAML 들여쓰기)를 정확히 만들어내는지 확인한다.
 */
class BackendComposeScriptTest {

    // 로컬 구동 검증(앱+mysql, /db 200)과 동일한 compose.yml 본문. 비밀은 없다(.env 의 ${...} 치환).
    private static final String MYSQL_COMPOSE = """
            services:
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
              app:
                image: qeploy-app-5:latest
                depends_on:
                  db:
                    condition: service_healthy
                env_file:
                  - .env
                ports:
                  - "8080:8080"
                restart: unless-stopped
            volumes:
              dbdata:
            """;

    @Test
    void s3ComposeScriptLoadsImageWritesEnvAndBringsUpAppPlusMysql() {
        String s = BackendDeployRunner.dockerComposeUserDataScript(
                "bucket-x", "5/image.tar", "qeploy-app-5:latest",
                DatabaseEngine.MYSQL, 5L, 8080, "https://ask.qeploy.com");

        // 앱 이미지: S3 load
        assertThat(s).contains("aws s3 cp s3://bucket-x/5/image.tar /opt/app/image.tar");
        assertThat(s).contains("docker load -i /opt/app/image.tar");
        // SSM → .env (compose 작업 디렉터리, 앱 env_file + 변수치환 겸용)
        assertThat(s).contains("> /opt/app/.env");
        // compose 플러그인 확보 + up
        assertThat(s).contains("cli-plugins/docker-compose");
        assertThat(s).contains("docker compose -f /opt/app/compose.yml --project-directory /opt/app up -d");
        // 실제 구동 검증한 compose.yml 형태 그대로 생성되는지(YAML 들여쓰기 포함)
        assertThat(s).contains(MYSQL_COMPOSE);
        // 공통 HTTPS 종단
        assertThat(s).contains("reverse_proxy 127.0.0.1:8080");
        assertThat(s).startsWith("#!/bin/bash");
    }

    @Test
    void ecrComposeScriptPullsImageInsteadOfS3() {
        String registry = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com";
        String imageRef = registry + "/qeploy-app-5:latest";
        String s = BackendDeployRunner.ecrComposeUserDataScript(
                "ap-northeast-2", registry, imageRef, DatabaseEngine.MYSQL, 5L, 8080, "");

        assertThat(s).contains("docker pull " + imageRef);
        assertThat(s).doesNotContain("aws s3 cp");
        assertThat(s).contains("image: " + imageRef);   // app 서비스가 ECR 이미지 참조
        assertThat(s).contains("docker compose -f /opt/app/compose.yml");
    }

    @Test
    void postgresComposeUsesPostgresImageAndHealthcheck() {
        String s = BackendDeployRunner.dockerComposeUserDataScript(
                "b", "k", "app:latest", DatabaseEngine.POSTGRESQL, 5L, 8080, "");

        assertThat(s).contains("image: postgres:16");
        assertThat(s).contains("POSTGRES_PASSWORD: ${DB_PASSWORD}");
        assertThat(s).contains("POSTGRES_DB: ${DB_NAME}");
        assertThat(s).contains("pg_isready -U postgres");
        assertThat(s).doesNotContain("mysql");
    }
}
