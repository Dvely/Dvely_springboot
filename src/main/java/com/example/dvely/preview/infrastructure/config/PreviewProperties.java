package com.example.dvely.preview.infrastructure.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "qeploy.preview")
public class PreviewProperties {

    private String gatewayBaseUrl = "http://localhost:8080";
    private Duration ttl = Duration.ofMinutes(30);
}
