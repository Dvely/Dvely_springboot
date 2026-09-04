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
        String bundledDbEngine
) {}
