package com.example.dvely.project.application.command.dto;

import java.util.Locale;

public enum ProjectDeleteMode {
    PROJECT_ONLY,
    PROJECT_AND_REPOSITORY;

    public static ProjectDeleteMode from(String value) {
        if (value == null || value.isBlank()) {
            return PROJECT_ONLY;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("PROJECT_ONLY".equals(normalized) || "PROJECT".equals(normalized)) {
            return PROJECT_ONLY;
        }
        if ("PROJECT_AND_REPOSITORY".equals(normalized) || "PROJECT_WITH_REPOSITORY".equals(normalized)) {
            return PROJECT_AND_REPOSITORY;
        }

        throw new IllegalArgumentException("deleteMode must be PROJECT_ONLY or PROJECT_AND_REPOSITORY");
    }
}
