package com.example.dvely.provisioning.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "EC2 백엔드 서버 생성 요청. 과금 자원이라 승인을 거칩니다.")
public record CreateServerRequest(
        @Schema(description = "인스턴스 티어. 생략 시 t3.micro(프리티어).", example = "t3.micro")
        String instanceType,

        @Schema(description = "배포 형태. NATIVE=jar 를 java -jar / DOCKER=앱 이미지를 docker run. "
                + "생략 시 NATIVE. DOCKER 는 저장소 루트에 Dockerfile 이 있어야 스택 무관하게 배포된다.",
                allowableValues = {"NATIVE", "DOCKER"}, example = "NATIVE", nullable = true)
        String deployMode,

        @Schema(description = "번들 DB 엔진. 값이 있으면 같은 EC2 에 이 DB 컨테이너를 compose 로 함께 띄우고 "
                + "앱을 그 DB 로 배선한다(RDS 없이 앱+DB 한 인스턴스). DOCKER 배포에서만 유효. 생략 시 번들 DB 없음.",
                allowableValues = {"MYSQL", "POSTGRESQL"}, example = "MYSQL", nullable = true)
        String bundledDbEngine,

        @Schema(description = "웹(프론트) 컨테이너 — split: 별도 프론트 저장소(owner/repo). 값이 있으면 같은 EC2 에 "
                + "프론트 nginx 컨테이너를 함께 띄운다(같은 오리진). DOCKER 배포에서만 유효.",
                example = "dldnsgkr/dvely-fe", nullable = true)
        String frontendRepo,

        @Schema(description = "웹(프론트) 컨테이너 — 모노: 프론트가 있는 하위폴더(백엔드 레포 기준). frontendRepo 와 "
                + "함께면 그 레포의 하위폴더. 값이 있으면(레포/폴더 중 하나라도) 웹 컨테이너 활성.",
                example = "frontend", nullable = true)
        String frontendDir,

        @Schema(description = "백엔드 API 프리픽스 — nginx 가 이 경로를 백엔드로 프록시하고 나머지는 프론트 SPA. "
                + "콤마로 여러 개 가능. 생략 시 /api. 웹 컨테이너를 쓸 때만 의미 있음.",
                example = "/api", nullable = true)
        String apiPathPrefix
) {}
