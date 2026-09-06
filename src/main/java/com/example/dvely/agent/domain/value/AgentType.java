package com.example.dvely.agent.domain.value;

public enum AgentType {
    // 빌드 전 스펙 되묻기. 요청이 핵심 스펙(백엔드 스택 등)에서 추측해야만 하는 애매함을 가지면 결정
    // 에이전트가 이 스텝 '하나만' 내서 사용자에게 구조화 질문(라디오/체크박스/텍스트)을 하고 멈춘다.
    // 답을 받으면 그 답으로 재-decide 해 일관된 플랜(RUNTIME_SETUP+CODE+... 스택 일치)으로 이어간다.
    CLARIFY,
    CHAT,
    CODE,
    DEPLOY,
    DOMAIN_BIND,
    INFRA_OPERATE,
    RUNTIME_SETUP,
    // 운영 백엔드 배포(C2 agent 통합): 프리뷰가 아니라 사용자 AWS 계정에 RDS+EC2 로 백엔드를
    // 실제 배포한다. DEPLOY(GitHub Pages 정적)·RUNTIME_SETUP(프리뷰 백엔드)과 구분된다.
    BACKEND_DEPLOY
}
