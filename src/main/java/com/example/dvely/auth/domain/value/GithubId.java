package com.example.dvely.auth.domain.value;

public record GithubId(String value) {
    public GithubId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GithubId cannot be blank");
        }
    }
}
