package com.example.dvely.project.application.result;

import java.time.OffsetDateTime;

public record CommitResult(
        String sha,
        String message,
        String author,
        OffsetDateTime committedAt
) {
}
