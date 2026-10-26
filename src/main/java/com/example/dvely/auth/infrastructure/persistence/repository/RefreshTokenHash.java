package com.example.dvely.auth.infrastructure.persistence.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 리프레시 토큰은 DB 에 SHA-256 hex 로만 남는다(#506). 원문은 발급 응답에 한 번 실리고 끝난다 —
 * 테이블이 새어도 그 값으로 토큰을 회전시킬 수 없다.
 *
 * <p>솔트를 두지 않는다. 원문이 무작위 UUID(122비트)라 사전 공격 대상이 아니고, 조회가 해시 일치
 * 하나로 끝나야 한다. 기존 평문 행을 바꾼 마이그레이션의 {@code SHA2(token, 256)} 과 같은 값(소문자
 * hex)을 내야 이전 토큰이 그대로 이어진다.</p>
 */
final class RefreshTokenHash {

    private RefreshTokenHash() {
    }

    static String of(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
    }
}
