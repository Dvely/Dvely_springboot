package com.example.dvely.environment.domain.repository;

import java.time.LocalDateTime;

/**
 * 환경변수 목록 전용 읽기 모델. U6(#341) 6-5.
 *
 * <p><b>값(env_value)이 없다.</b> 그 컬럼은 MEDIUMTEXT + {@code @Convert(AesEncryptor)} 라 엔티티로
 * 읽으면 행마다 AES 복호화가 돈다. 그런데 목록 응답은 secret 인 변수의 값을 언제나 null 로 내보낸다
 * (design D4) — 복호화해서 버리고 있었다.</p>
 *
 * <p>그래서 값은 이 뷰에 담지 않고, <b>secret 이 아닌 행에 대해서만</b> 따로 한 번 더 읽는다
 * ({@code EnvironmentVariableRepository#findPlainValuesByIds}). 비밀 값은 애초에 메모리에 올라오지
 * 않으므로, "응답 매핑에서 마스킹한다" 에 의존하던 예전 구조보다 강하다 — 여기에 value 필드를
 * 추가하지 말 것.</p>
 */
public record EnvironmentVariableSummaryView(
        Long id,
        String scope,
        String key,
        boolean secret,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
