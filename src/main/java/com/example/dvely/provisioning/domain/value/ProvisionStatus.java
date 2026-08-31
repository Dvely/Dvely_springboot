package com.example.dvely.provisioning.domain.value;

/**
 * DB 프로비저닝 자원의 상태. FE 가 이 값으로 폴링을 계속할지(전이 상태) 멈출지(종료 상태) 정한다.
 *
 * EXPIRED 가 있는 이유: LOCAL DB 는 프리뷰 세션(TTL 30분)과 함께 사라진다. 그때 상태를 READY 로
 * 두면 화면에는 살아 있는데 실제로는 없는 자원이 남는다 — 프리뷰에서 겪은 것과 같은 함정이다.
 * 만료되면 EXPIRED 로 바꿔, "READY 인데 실제로는 없는" 상태를 만들지 않는다.
 */
public enum ProvisionStatus {
    // 전이 상태 — FE 는 이 값이 하나라도 있으면 폴링을 계속한다
    PENDING,        // 요청 접수, 아직 시작 안 함
    PROVISIONING,   // 생성 중 (RDS 는 5~10분)

    // 종료 상태 — 전부 이 값이면 폴링을 멈춘다
    READY,          // 접속 가능
    FAILED,         // 생성 실패 (errorCode 로 원인 구분)
    EXPIRED         // LOCAL: 세션 만료로 사라짐
}
