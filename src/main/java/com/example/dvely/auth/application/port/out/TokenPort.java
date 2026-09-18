package com.example.dvely.auth.application.port.out;

import java.time.LocalDateTime;

public interface TokenPort {
    String createToken(Long userId);

    /**
     * 토큰을 한 번만 파싱해 필요한 클레임을 한꺼번에 돌려준다.
     *
     * <p>클레임 두 개 이상이 필요한 호출자(인증 필터, 로그아웃)는 아래 단건 getter 대신 이것을 쓴다 —
     * 단건 getter 를 여러 번 부르면 그 횟수만큼 서명 검증이 반복된다.</p>
     */
    TokenClaims parseClaims(String token);

    Long getUserId(String token);
    String getJti(String token);
    LocalDateTime getExpiresAt(String token);
}
