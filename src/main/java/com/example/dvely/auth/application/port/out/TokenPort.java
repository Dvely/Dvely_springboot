package com.example.dvely.auth.application.port.out;

import java.time.LocalDateTime;

public interface TokenPort {
    String createToken(Long userId);
    Long getUserId(String token);
    String getJti(String token);
    LocalDateTime getExpiresAt(String token);
}
