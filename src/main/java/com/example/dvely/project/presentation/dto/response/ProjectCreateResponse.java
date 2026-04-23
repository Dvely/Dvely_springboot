package com.example.dvely.project.presentation.dto.response;

public record ProjectCreateResponse(
        Long projectId,
        String name,
        String status
) {
}
