package com.example.dvely.provisioning.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "EC2 백엔드 서버 생성 요청. 과금 자원이라 승인을 거칩니다.")
public record CreateServerRequest(
        @Schema(description = "인스턴스 티어. 생략 시 t3.micro(프리티어).", example = "t3.micro")
        String instanceType
) {}
