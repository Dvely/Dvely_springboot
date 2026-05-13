package com.example.dvely.auth.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "GitHub 리다이렉트 URL 응답")
public record GithubUrlResponse(
        @Schema(description = "프론트엔드에서 리다이렉트할 GitHub URL") String url
) {
}
