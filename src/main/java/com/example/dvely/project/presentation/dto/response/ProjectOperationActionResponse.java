package com.example.dvely.project.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Overview 운영 조치")
public record ProjectOperationActionResponse(
        String type,
        boolean available,
        String reason
) {
}
