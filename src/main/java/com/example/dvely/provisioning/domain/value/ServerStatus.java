package com.example.dvely.provisioning.domain.value;

/**
 * EC2 백엔드 서버 프로비저닝 상태. 승인 전(PENDING) → 승인 후 워커 대기(QUEUED) → 워커가 빌드
 * (BUILDING) → 인스턴스 생성 대기(PROVISIONING) → 헬스체크 통과(RUNNING). 실패는 FAILED, 종료는
 * TERMINATED. RDS 와 달리 빌드·다중 AWS 호출이 수 분이라, 무거운 실행은 승인 트랜잭션이 아니라
 * 워커가 QUEUED 를 집어서 한다.
 */
public enum ServerStatus {
    PENDING,        // 승인 대기
    QUEUED,         // 승인됨 — 배포 워커 대기
    BUILDING,       // 워커가 소스 빌드 중(jar)
    PROVISIONING,   // 인스턴스 생성됨, running/헬스체크 대기
    RUNNING,        // 헬스체크 통과 — 접속 가능
    FAILED,
    TERMINATED      // 종료됨(과금 정지)
}
