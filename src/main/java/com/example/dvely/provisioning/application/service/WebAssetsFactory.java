package com.example.dvely.provisioning.application.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 웹(프론트) 컨테이너의 nginx 설정과 기본 Dockerfile 을 만든다. 웹 컨테이너의 두 convention 을 코드로
 * 못박는다: (1) 백엔드 API 는 단일(또는 몇 개) 프리픽스 뒤에 있고 나머지는 프론트(SPA), (2) 프론트는
 * 상대경로로 그 프리픽스를 호출한다(같은 오리진 → CORS 불필요). 순수 함수라 컨테이너 없이 단위테스트한다.
 *
 * <p>여기서 만드는 nginx.conf·Dockerfile 형태는 로컬 compose 로 실구동 검증했다(프론트 서빙 / {@code /api}
 * 프록시 / SPA 폴백).</p>
 */
final class WebAssetsFactory {

    static final String DEFAULT_API_PREFIX = "/api";

    private WebAssetsFactory() {
    }

    /** apiPathPrefix 설정을 프리픽스 목록으로 판다(콤마 다중 허용). 각 프리픽스는 앞에 /, 끝 / 제거로 정규화. */
    static List<String> parsePrefixes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of(DEFAULT_API_PREFIX);
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (!t.startsWith("/")) {
                t = "/" + t;
            }
            while (t.endsWith("/") && t.length() > 1) {
                t = t.substring(0, t.length() - 1);
            }
            out.add(t);
        }
        return out.isEmpty() ? List.of(DEFAULT_API_PREFIX) : out;
    }

    /**
     * nginx.conf. 각 프리픽스는 app 컨테이너로 프록시(프리픽스 유지), 나머지는 정적+SPA 폴백. upstream 은
     * 변수로 둬 <b>지연 해석</b>한다 — app 이 아직 안 떠도 nginx 는 기동한다(compose 기동 순서 함정 회피).
     */
    static String nginxConf(List<String> prefixes) {
        StringBuilder locations = new StringBuilder();
        for (String prefix : prefixes) {
            locations.append("""
                        location %s/ {
                            set $upstream http://app:8080;
                            proxy_pass $upstream;
                            proxy_set_header Host $host;
                            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                        }
                    """.formatted(prefix));
        }
        return """
                server {
                    listen 80;
                    resolver 127.0.0.11 valid=10s;
                %s    location / {
                        root /usr/share/nginx/html;
                        try_files $uri $uri/ /index.html;
                    }
                }
                """.formatted(locations.toString());
    }

    /**
     * 기본 web Dockerfile. 프론트를 빌드해(npm run build) 출력(dist|build|out)을 정규화한 뒤 nginx 로 서빙
     * 하고, 생성한 nginx.conf 를 심는다. 프론트에 자체 Dockerfile 이 있으면 그걸 우선(이건 폴백).
     */
    static String webDockerfile() {
        return """
                FROM node:20-alpine AS build
                WORKDIR /app
                COPY package*.json ./
                RUN npm ci 2>/dev/null || npm install
                COPY . .
                RUN npm run build
                RUN set -e; for d in dist build out; do [ -d "$d" ] && { cp -r "$d" /site; break; }; done; [ -d /site ] || { echo "프론트 빌드 출력(dist|build|out) 없음"; exit 1; }
                FROM nginx:alpine
                COPY --from=build /site/ /usr/share/nginx/html/
                COPY nginx.conf /etc/nginx/conf.d/default.conf
                """;
    }
}
