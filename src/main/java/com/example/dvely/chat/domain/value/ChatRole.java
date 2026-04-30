package com.example.dvely.chat.domain.value;

import java.util.Locale;

public enum ChatRole {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    private final String storageValue;

    ChatRole(String storageValue) {
        this.storageValue = storageValue;
    }

    public String toStorage() {
        return storageValue;
    }

    public static ChatRole fromStorage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ChatRole role : values()) {
            if (role.storageValue.equals(normalized)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown role: " + value);
    }
}
