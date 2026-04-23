package com.example.dvely.auth.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "encrypt")
public record EncryptProperties(String secret) {}
