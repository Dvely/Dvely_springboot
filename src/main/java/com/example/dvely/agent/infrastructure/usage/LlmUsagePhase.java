package com.example.dvely.agent.infrastructure.usage;

/** 한 LLM 호출이 파이프라인의 어느 구간에서 났는가. 어디에 돈이 쓰이는지 가르는 축이다. */
public enum LlmUsagePhase {

    /** 계획 수립(DecisionAgentService) — 교정 재시도 호출도 여기 들어간다. */
    DECISION,

    /** 계획 실행(CODE 툴 루프·CHAT·DEPLOY 등). 태스크당 토큰 예산이 걸리는 구간이다. */
    AGENT_RUN,

    /** 배포 실패 로그 요약 1회. */
    DEPLOY_FAILURE_ANALYSIS,

    /** 열린 스코프 없이 난 호출. 태스크에 귀속되지 않지만 합계에서 빠지지는 않는다. */
    UNSCOPED
}
