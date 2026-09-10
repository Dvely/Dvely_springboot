package com.example.dvely.auth.application.port.out;

import java.time.LocalDateTime;

/**
 * 서명 검증까지 끝난 서비스 JWT 의 클레임 묶음.
 *
 * <p>존재 이유는 순전히 호출 횟수다. 인증 필터는 한 요청에서 userId 와 jti 를 둘 다 필요로 하는데,
 * 클레임을 하나씩 돌려주는 API 만 있으면 그 두 값을 얻기 위해 HMAC 키 생성 + 파서 빌드 + 서명 검증을
 * 두 번 하게 된다. 한 번 파싱한 결과를 통째로 넘겨 그 중복을 구조적으로 없앤다.</p>
 */
public record TokenClaims(Long userId, String jti, LocalDateTime expiresAt) {
}
