package com.example.dvely.agent.domain.value;

public enum AgentType {
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
