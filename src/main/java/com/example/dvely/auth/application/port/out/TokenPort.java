package com.example.dvely.auth.application.port.out;

public interface TokenPort {
    String createToken(Long userId);
}
