package com.example.dvely.auth.application.port.out;

import java.time.LocalDateTime;

public interface TokenBlacklistPort {
    void revoke(String jti, LocalDateTime expiresAt);
    boolean isRevoked(String jti);
}
