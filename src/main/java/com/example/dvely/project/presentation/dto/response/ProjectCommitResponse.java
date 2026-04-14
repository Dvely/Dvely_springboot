package com.example.dvely.project.presentation.dto.response;

import java.time.OffsetDateTime;

public record ProjectCommitResponse(
        String sha,
        String message,
        String author,
        OffsetDateTime committedAt
) {
}
