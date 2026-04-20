package com.example.dvely.auth.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "installation_access_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstallationTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long installationId;

    @Column(nullable = false, length = 512)
    private String token;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    public InstallationTokenEntity(Long installationId, String token, LocalDateTime expiresAt) {
        this.installationId = installationId;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public void update(String token, LocalDateTime expiresAt) {
        this.token = token;
        this.expiresAt = expiresAt;
    }

    // 만료 5분 전부터는 유효하지 않다고 판단해 미리 재발급
    public boolean isValid() {
        return LocalDateTime.now().plusMinutes(5).isBefore(expiresAt);
    }
}
