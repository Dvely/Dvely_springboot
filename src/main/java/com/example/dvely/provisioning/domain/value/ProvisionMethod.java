package com.example.dvely.provisioning.domain.value;

/**
 * DB를 마련하는 방식.
 *
 * LOCAL 은 프리뷰 컨테이너 안에 DB 컨테이너를 띄우는 테스트용이라 자격 증명도 과금도 없다.
 * 세션이 만료되면 함께 사라진다. RDS·DOCKER 는 사용자 AWS 에 영속 리소스를 만드는 실배포용이라
 * CloudConnection 자격이 필요하고 과금된다 — 그래서 이 둘만 승인 게이트를 거친다.
 */
public enum ProvisionMethod {
    LOCAL,   // 프리뷰 컨테이너 안 DB 컨테이너 (테스트)
    RDS,     // 사용자 AWS 관리형 DB
    DOCKER   // 사용자 AWS EC2 위 DB 컨테이너
}
