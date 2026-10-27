package com.example.dvely.preview.infrastructure.egress;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** 프리뷰 egress 기본 허용목록. 2026-10-27 dev 실측(같은 tinyproxy 필터)으로 보정한 호스트를 고정한다. */
class PreviewEgressAllowlistDefaultsTest {

    @Test
    void theDefaultsCoverGradleWrapperPluginArtifactsAndPrismaEngines() throws IOException {
        List<String> hosts = defaults();

        assertThat(hosts).contains(
                "release-assets.githubusercontent.com",   // gradle wrapper 배포본의 실제 위치
                "plugins-artifacts.gradle.org",           // 플러그인 포털 아티팩트
                "binaries.prisma.sh",                     // prisma 엔진
                "services.gradle.org", "plugins.gradle.org", "repo.maven.apache.org", "registry.npmjs.org");
    }

    @Test
    void broadHostsStayOut() throws IOException {
        // storage.googleapis.com 은 버킷 전체를 연다. 필요한 도구는 다운로드를 끄는 쪽으로 다룬다.
        assertThat(defaults()).doesNotContain("storage.googleapis.com", "download.cypress.io");
    }

    private static List<String> defaults() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        String raw = sources.stream()
                .map(source -> source.getProperty("qeploy.preview.egress.allowed-hosts"))
                .filter(value -> value != null)
                .map(Object::toString)
                .findFirst()
                .orElseThrow();
        // ${QEPLOY_PREVIEW_EGRESS_ALLOWED_HOSTS:기본값} 의 기본값 부분
        String defaults = raw.substring(raw.indexOf(':') + 1, raw.lastIndexOf('}'));
        return Arrays.stream(defaults.split(",")).map(String::trim).filter(h -> !h.isEmpty()).toList();
    }
}
