package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * user-data 스크립트 생성 검증. NATIVE(java -jar)·DOCKER(docker load/run)가 각자 맞는 실행부를 만들고,
 * HTTPS 종단(Caddy·ask)은 두 모드 공통으로 붙는지 확인한다.
 */
class BackendUserDataScriptTest {

    @Test
    void nativeScriptRunsJarAndSharesHttpsSection() {
        String s = BackendDeployRunner.userDataScript("bucket-x", "7/app.jar", 7L, 8080, "https://ask.qeploy.com");

        assertThat(s).contains("aws s3 cp s3://bucket-x/7/app.jar /opt/app/app.jar");
        assertThat(s).contains("nohup java -jar /opt/app/app.jar --server.port=8080");
        assertThat(s).doesNotContain("docker load");
        assertThat(s).doesNotContain("docker run");
        // 공통 HTTPS 종단
        assertThat(s).contains("reverse_proxy 127.0.0.1:8080");
        assertThat(s).contains("ASK_BASE = \"https://ask.qeploy.com\"");
        assertThat(s).contains("on_demand");
    }

    @Test
    void dockerScriptLoadsAndRunsImageWithEnvFileAndSharesHttpsSection() {
        String s = BackendDeployRunner.dockerUserDataScript(
                "bucket-x", "7/image.tar", 7L, "qeploy-app-7:latest", 8080, "https://ask.qeploy.com");

        assertThat(s).contains("dnf install -y python3 docker");
        assertThat(s).contains("systemctl enable --now docker");
        assertThat(s).contains("aws s3 cp s3://bucket-x/7/image.tar /opt/app/image.tar");
        assertThat(s).contains("docker load -i /opt/app/image.tar");
        // SSM env 를 env 파일로 → 컨테이너에만 주입(호스트 export 아님)
        assertThat(s).contains("> /opt/app/app.env");
        assertThat(s).contains("docker run -d --restart unless-stopped -p 8080:8080 --env-file /opt/app/app.env qeploy-app-7:latest");
        assertThat(s).doesNotContain("java -jar");
        // 공통 HTTPS 종단 — native 와 동일하게 localhost:port 프록시
        assertThat(s).contains("reverse_proxy 127.0.0.1:8080");
        assertThat(s).contains("ASK_BASE = \"https://ask.qeploy.com\"");
        assertThat(s).contains("on_demand");
    }

    @Test
    void bothStartWithShebang() {
        String nat = BackendDeployRunner.userDataScript("b", "k", 1L, 8080, "");
        String doc = BackendDeployRunner.dockerUserDataScript("b", "k", 1L, "img:latest", 8080, "");
        assertThat(nat).startsWith("#!/bin/bash");
        assertThat(doc).startsWith("#!/bin/bash");
    }
}
