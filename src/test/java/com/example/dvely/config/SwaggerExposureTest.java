package com.example.dvely.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** dev·prod 에서는 Swagger UI·OpenAPI 문서가 익명에게 열리지 않는다(#505). */
class SwaggerExposureTest {

    @ParameterizedTest
    @ValueSource(strings = {"application-dev.yml", "application-prod.yml"})
    void deployedProfilesTurnSpringdocOff(String file) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));

        assertThat(sources).anySatisfy(source -> {
            assertThat(source.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
            assertThat(source.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(false);
        });
    }

    // 속성이 달라 컨텍스트 캐시에 새 컨텍스트가 하나 더 생긴다. 남겨 두면 전체 실행의 힙을 잡아먹어
    // 뒤의 무거운 테스트가 OOM 으로 죽는다(실측) — 이 클래스가 끝나면 닫는다.
    @Nested
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
    class WhenSpringdocIsOff {

        @Autowired private WebApplicationContext context;
        @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;

        @Test
        void documentPathsRequireAuthentication() throws Exception {
            // permitAll 이 남아 있으면 여기서 401 이 아니라 404 가 나온다 — 설정이 다시 켜지면 그대로 열린다.
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();

            mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        }
    }
}
