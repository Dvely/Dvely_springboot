package com.example.dvely.preview.domain.value;

/**
 * 프리뷰 세션의 수명 상태.
 *
 * <p>{@link #ACTIVE}만 게이트웨이 프록시가 열어준다({@code PreviewSessionService#resolveGateway}) —
 * 나머지 상태의 세션 URL은 404다. Agent CODE 스텝이 만드는 세션은 컨테이너를 만든 직후 곧바로
 * ACTIVE로 시작하지만(작업 스텝 자체가 준비를 끝낸 뒤 호출하므로), 프로젝트 단위 프리뷰는 clone →
 * npm install → build → serve 가 끝나야 볼 수 있는 것이 있어서 {@link #PROVISIONING}을 거친다.</p>
 */
public enum PreviewSessionStatus {

    /** 컨테이너가 떠 있고 프록시로 접근 가능. */
    ACTIVE,

    /**
     * 컨테이너는 떴지만 워크스페이스 준비(clone/install/build/serve)가 아직 진행 중.
     *
     * <p>프로젝트 단위 프리뷰에만 나타난다. 준비가 끝나면 ACTIVE, 실패하면 {@link #FAILED}로 간다.
     * 앱이 준비 도중 재시작되어 어느 쪽으로도 가지 못한 행은 만료 시각이 지난 뒤
     * {@code PreviewSessionService#cleanupExpired}가 FAILED로 쓸어담는다.</p>
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
