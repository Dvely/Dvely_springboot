package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WebAssetsFactoryTest {

    @Test
    @DisplayName("프리픽스 파싱: 기본 /api, 콤마 다중, 정규화(앞 / · 끝 / 제거)")
    void parsePrefixes() {
        assertThat(WebAssetsFactory.parsePrefixes(null)).containsExactly("/api");
        assertThat(WebAssetsFactory.parsePrefixes("  ")).containsExactly("/api");
        assertThat(WebAssetsFactory.parsePrefixes("/api")).containsExactly("/api");
        assertThat(WebAssetsFactory.parsePrefixes("api")).containsExactly("/api");     // 앞 / 보정
        assertThat(WebAssetsFactory.parsePrefixes("/api/")).containsExactly("/api");   // 끝 / 제거
        assertThat(WebAssetsFactory.parsePrefixes("/api, /auth ,graphql"))
                .containsExactly("/api", "/auth", "/graphql");
    }

    @Test
    @DisplayName("nginx.conf: 로컬 구동검증본의 요소(프리픽스 프록시 유지·지연해석·SPA 폴백)를 담는다")
    void nginxConfHasProvenElements() {
        String conf = WebAssetsFactory.nginxConf(List.of("/api"));
        assertThat(conf).contains("listen 80;");
        assertThat(conf).contains("resolver 127.0.0.11 valid=10s;");   // 지연 해석
        assertThat(conf).contains("location /api/ {");
        assertThat(conf).contains("set $upstream http://app:8080;");   // 변수 upstream(지연해석)
        assertThat(conf).contains("proxy_pass $upstream;");
        assertThat(conf).contains("try_files $uri $uri/ /index.html;");   // SPA 폴백
        assertThat(conf).contains("root /usr/share/nginx/html;");
    }

    @Test
    @DisplayName("nginx.conf: 다중 프리픽스면 각각 location 블록")
    void nginxConfMultiplePrefixes() {
        String conf = WebAssetsFactory.nginxConf(List.of("/api", "/auth"));
        assertThat(conf).contains("location /api/ {");
        assertThat(conf).contains("location /auth/ {");
    }

    @Test
    @DisplayName("nginx.conf(정적 전용): 웹 전용은 app 프록시 없이 SPA 폴백만")
    void nginxConfStaticOnlyHasNoProxy() {
        String conf = WebAssetsFactory.nginxConfStaticOnly();
        assertThat(conf).contains("listen 80;");
        assertThat(conf).contains("try_files $uri $uri/ /index.html;");   // SPA 폴백
        assertThat(conf).contains("root /usr/share/nginx/html;");
        // 백엔드가 없으므로 프록시 관련 요소는 없어야 한다
        assertThat(conf).doesNotContain("proxy_pass");
        assertThat(conf).doesNotContain("http://app:8080");
        assertThat(conf).doesNotContain("location /api/");
    }

    @Test
    @DisplayName("web Dockerfile: root 서빙용 base 강제(Vite=--base=/, 그 외=PUBLIC_URL=/)")
    void webDockerfileForcesRootBase() {
        String df = WebAssetsFactory.webDockerfile();
        // GitHub Pages 용 하위경로 base 로 빌드된 앱이 root 서빙에서 빈 화면 나는 것 방지(실 e2e 발견)
        assertThat(df).contains("grep -q '\"vite\"' package.json");   // 프레임워크 분기
        assertThat(df).contains("npm run build -- --base=/");          // Vite → root base
        assertThat(df).contains("PUBLIC_URL=/ npm run build");         // CRA 등 → root base
    }

    @Test
    @DisplayName("web Dockerfile: 빌드→출력 정규화(dist|build|out)→nginx + nginx.conf 심기")
    void webDockerfileShape() {
        String df = WebAssetsFactory.webDockerfile();
        assertThat(df.stripLeading()).startsWith("FROM node:20-alpine AS build");
        assertThat(df).contains("npm run build");
        assertThat(df).contains("for d in dist build out;");   // 출력 자동 정규화
        assertThat(df).contains("FROM nginx:alpine");
        assertThat(df).contains("COPY --from=build /site/ /usr/share/nginx/html/");
        assertThat(df).contains("COPY nginx.conf /etc/nginx/conf.d/default.conf");
    }
}
