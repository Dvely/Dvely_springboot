package com.example.dvely.project.presentation.dto.request;

import jakarta.validation.constraints.NotNull;

public record UpdateProjectInfrastructureSettingsRequest(
        @NotNull Long cloudConnectionId
) {
}
