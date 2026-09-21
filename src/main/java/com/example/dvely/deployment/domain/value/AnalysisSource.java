package com.example.dvely.deployment.domain.value;

/** Where a {@code DeploymentFailureAnalysis}'s summary/suggestedFix came from. */
public enum AnalysisSource {
    // 지금은 아무 데서도 만들지 않는다(#364: 서버 키 LLM 호출 제거). 지우지 않는 이유는 둘이다 —
    // 이미 저장된 행이 이 값을 갖고 있어 AnalysisSource.valueOf 가 그 행을 읽다 죽지 않아야 하고,
    // 나중에 BYOK 기반 요약을 붙일 자리이기 때문이다.
    LLM,
    RULE_BASED
}
