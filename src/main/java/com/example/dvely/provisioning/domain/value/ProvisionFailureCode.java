package com.example.dvely.provisioning.domain.value;

/**
 * 프로비저닝 실패 분류. errorMessage 는 상세, 이 코드는 FE 가 문구를 매핑할 분류다(#168 과 동형).
 * 실제로 발생하는 값만 둔다 — "코드엔 있는데 실무엔 안 뜨는" 분류는 화면 분기를 헷갈리게 한다.
 */
public enum ProvisionFailureCode {
    IAM_PERMISSION,     // 사용자 자격에 필요한 권한이 없음
    QUOTA_EXCEEDED,     // 계정 리소스 한도 초과
    ENGINE_UNSUPPORTED, // 요청 엔진을 그 방식이 지원 안 함
    PROVIDER_ERROR,     // 그 외 클라우드/도커 오류
    // 사용자 자격 자체가 만료/무효 — 권한 부족(IAM_PERMISSION)과 다르다. 권한은 정책을 고치면
    // 되지만 이쪽은 자격을 다시 등록해야 한다. 실제로 발생한다(#429, dev 에서 ExpiredToken 실측).
    INVALID_CREDENTIAL
}
