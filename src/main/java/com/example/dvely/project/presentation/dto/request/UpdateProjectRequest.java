package com.example.dvely.project.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateProjectRequest(@NotBlank String name) {
}
