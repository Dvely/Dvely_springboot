package com.example.dvely.cloudconnection.domain.repository;

import java.time.LocalDateTime;

/**
 * 클라우드 연결 목록 전용 읽기 모델. U6(#341) 6-5.
 *
 * <p><b>비밀 값이 이 레코드에 들어올 자리는 없다.</b> {@code secret_access_key}·{@code session_token}·
 * {@code service_account_key_json} 은 MEDIUMTEXT 이고 {@code @Convert(AesEncryptor)} 가 붙어 있어
 * 엔티티로 읽으면 <b>행마다 AES 복호화</b>가 돈다. 그런데 목록 응답이 쓰는 것은 "설정돼 있는가"
 * 세 개의 boolean 뿐이다 — 그래서 쿼리가 {@code is not null} 만 묻고 값 자체를 읽지 않는다.</p>
 *
 * <p>평문이 응답에서 걸러지는 것에 의존하던 예전 구조보다 강하다: 평문이 애초에 메모리에
 * 올라오지 않는다. 여기에 비밀 컬럼을 담는 필드를 추가하지 말 것 — 그 순간 유출 경로가 생긴다.</p>
 */
public record CloudConnectionSummaryView(
        Long id,
        String provider,
        String displayName,
        String accountId,
        String region,
        String roleArn,
        String awsCredentialType,
        String accessKeyId,
        boolean secretAccessKeyConfigured,
        boolean sessionTokenConfigured,
        String gcpCredentialType,
        boolean serviceAccountKeyConfigured,
        String gcpProjectId,
        String serviceAccountEmail,
        String status,
        LocalDateTime lastCheckedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
