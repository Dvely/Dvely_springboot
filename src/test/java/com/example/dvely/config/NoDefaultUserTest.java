package com.example.dvely.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.dvely.auth.infrastructure.config.security.JwtProvider;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 기본 사용자 자동 구성(UserDetailsServiceAutoConfiguration)을 끈 뒤에도 인증이 그대로인지 본다. 켜져 있으면 기동할
 * 때마다 쓰지 않는 사용자와 비밀번호를 만들어 로그에 찍었다("Using generated security password").
 */
@SpringBootTest
class NoDefaultUserTest {

    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @Autowired private JwtProvider jwtProvider;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    }

    @Test
    void noGeneratedUserExists() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void protectedEndpointsStillNeedAToken() throws Exception {
        mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
        // 폼·Basic 로그인은 원래 없다 — 기본 사용자 이름으로 Basic 을 보내도 열리지 않는다
        mvc.perform(get("/api/v1/projects").header("Authorization", "Basic dXNlcjpwYXNzd29yZA=="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aValidJwtStillGetsIn() throws Exception {
        mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer " + jwtProvider.createToken(987654321L)))
                .andExpect(status().isOk());
    }
}
