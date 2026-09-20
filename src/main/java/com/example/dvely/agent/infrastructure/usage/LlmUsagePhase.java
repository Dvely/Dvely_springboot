package com.example.dvely.agent.infrastructure.usage;

/** 한 LLM 호출이 파이프라인의 어느 구간에서 났는가. 어디에 돈이 쓰이는지 가르는 축이다. */
public enum LlmUsagePhase {

    /** 계획 수립(DecisionAgentService) — 교정 재시도 호출도 여기 들어간다. */
    DECISION,

    /** 계획 실행(CODE 툴 루프·CHAT·DEPLOY 등). 태스크당 토큰 예산이 걸리는 구간이다. */
    AGENT_RUN,

    /**
     * 배포 실패 로그 요약 1회.
     *
     * <p>#364 이후로는 새로 생산되지 않는다 — 그 경로에서 LLM 을 걷어내고 룰 기반만 남겼다. 상수를
     * 지우지 않는 것은 이미 저장된 행이 이 이름으로 읽히기 때문이고, BYOK 요약을 붙이면 다시 쓸
     * 자리이기 때문이다.</p>
     */
    DEPLOY_FAILURE_ANALYSIS,

    /** 열린 스코프 없이 난 호출. 태스크에 귀속되지 않지만 합계에서 빠지지는 않는다. */
    UNSCOPED
}
