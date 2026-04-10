package com.example.dvely.auth.infrastructure.config.security;

import com.example.dvely.auth.application.port.out.TokenPort;
import org.springframework.stereotype.Component;

@Component
public class JwtProvider implements TokenPort {

    @Override
    public String createToken(Long userId) {
        // TODO: 실제 JWT 라이브러리(jjwt 등)로 구현
        return "jwt-token-" + userId;
    }
}
