package com.example.dvely.preview.application.result;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 프리뷰 열람 권한 발급 결과 (Issue #77).
 *
 * @param previewUrl   회전된 accessToken이 반영된 새 주소. 이전 주소는 이 시점부터 열리지 않는다
 * @param cookieValue  게이트웨이가 소유권 증명으로 요구하는 쿠키 값
 * @param cookiePath   쿠키를 이 세션 경로로만 좁히기 위한 Path
 * @param cookieMaxAge 쿠키 수명. 세션 만료보다 길게 두지 않는다
 */
public record PreviewAccessGrant(
        String sessionId,
        String previewUrl,
        LocalDateTime expiresAt,
        String cookieValue,
        String cookiePath,
        Duration cookieMaxAge
) {
}
