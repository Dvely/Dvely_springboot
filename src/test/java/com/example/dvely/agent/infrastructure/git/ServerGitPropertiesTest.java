package com.example.dvely.agent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** 서버 측 git 플래그는 기본 꺼짐이고, 어느 프로파일도 아직 켜지 않는다 — 기존 컨테이너 경로가 그대로 돈다. */
class ServerGitPropertiesTest {

    @Test
    void flagsAreOffByDefault() {
        ServerGitProperties properties = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("qeploy.git.server-side", ServerGitProperties.class);

        assertThat(properties.pushEnabled()).isFalse();
        assertThat(properties.cloneEnabled()).isFalse();
        assertThat(properties.maxFiles()).isPositive();
        assertThat(properties.maxBytes()).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yaml", "application-dev.yml", "application-prod.yml"})
    void noProfileTurnsTheFlagsOnYet(String file) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));

        assertThat(sources).allSatisfy(source -> {
            assertThat(source.getProperty("qeploy.git.server-side.push-enabled")).isNotEqualTo(true);
            assertThat(source.getProperty("qeploy.git.server-side.clone-enabled")).isNotEqualTo(true);
        });
    }
}
