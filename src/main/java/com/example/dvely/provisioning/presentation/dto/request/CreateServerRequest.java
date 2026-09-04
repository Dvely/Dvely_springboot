package com.example.dvely.provisioning.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "EC2 백엔드 서버 생성 요청. 과금 자원이라 승인을 거칩니다.")
public record CreateServerRequest(
        @Schema(description = "인스턴스 티어. 생략 시 t3.micro(프리티어).", example = "t3.micro")
        String instanceType,

        @Schema(description = "배포 형태. NATIVE=jar 를 java -jar / DOCKER=앱 이미지를 docker run. "
                + "생략 시 NATIVE. DOCKER 는 저장소 루트에 Dockerfile 이 있어야 스택 무관하게 배포된다.",
                allowableValues = {"NATIVE", "DOCKER"}, example = "NATIVE", nullable = true)
        String deployMode
) {}
