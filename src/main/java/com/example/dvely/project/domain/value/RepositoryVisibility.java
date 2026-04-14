package com.example.dvely.project.domain.value;

import java.util.Locale;

public enum RepositoryVisibility {
    PRIVATE,
    PUBLIC;

    public static RepositoryVisibility from(String value) {
        if (value == null || value.isBlank()) {
            return PRIVATE;
        }
        return RepositoryVisibility.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}