package com.example.dvely.preview.domain.value;

/**
 * 프리뷰 세션의 수명 상태.
 *
 * ACTIVE 만 게이트웨이 프록시가 열어준다(PreviewSessionService#resolveGateway). 나머지 상태의
 * 세션 URL 은 404 다.
 *
 * 두 종류의 세션이 모두 PROVISIONING 을 거쳐 ACTIVE 로 간다. 프로젝트 단위 프리뷰는
 * clone → npm install → build → serve 가, Agent CODE 세션은 LLM 코드 작성 → 빌드 → serve 가
 * 끝나야 열 수 있기 때문이다.
 *
 * 예전에는 Agent 세션만 컨테이너를 띄운 직후 곧바로 ACTIVE 로 시작했다. "작업 스텝이 준비를
 * 끝낸 뒤 세션을 만든다"고 적혀 있었지만 실제 순서는 반대였다 — CodeAgentService 는 acquire 를
 * 맨 먼저 부르고 startPreviewServer 는 한참 뒤에 부른다. 그래서 ACTIVE 를 보고 iframe 을 붙인
 * FE 가 빌드가 끝날 때까지 502 만 보는 문제가 있었다.
 */
public enum PreviewSessionStatus {

    /** 컨테이너가 떠 있고 프록시로 접근 가능. */
    ACTIVE,

    /**
     * 컨테이너는 떴지만 그 안에서 서빙할 준비가 아직 끝나지 않음.
     *
     * 두 경로 모두 여기를 거친다. 프로젝트 단위 프리뷰는 ProjectPreviewProvisioner 가,
     * Agent CODE 세션은 CodeAgentService 가 startPreviewServer 성공 후
     * PreviewSessionService#markServing 으로 ACTIVE 로 올린다. 실패하면 markServeFailed 가
     * 사유와 함께 FAILED 로 닫는다.
     *
     * 앱이 준비 도중 재시작되어 어느 쪽으로도 가지 못한 행은 만료 시각이 지난 뒤
     * PreviewSessionService#cleanupExpired 가 FAILED 로 쓸어담는다.
     */
    PROVISIONING,

    /** TTL 만료로 정리됨(컨테이너 제거). */
    EXPIRED,

    /** 사용자가 명시적으로 종료함(컨테이너 제거). */
    CLOSED,

    /**
     * 프로비저닝이 실패해 프리뷰를 띄우지 못함(컨테이너 제거).
     *
     * <p>실패 사유는 {@code failure_reason} 컬럼에 남는다 — 컨테이너가 이미 제거된 뒤라
     * {@code /preview-sessions/{id}/logs}로는 빌드 로그를 볼 수 없기 때문이다.</p>
     */
    FAILED
}
