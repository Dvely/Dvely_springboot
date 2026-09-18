package com.example.dvely.common.worker;

/**
 * 1초 폴링 워커가 감시하는 작업 큐. {@link WorkerPollGate} 의 백오프 상태와 깨우기 신호가
 * 이 값 단위로 갈린다 — 배포 큐에 일이 들어왔다고 웹훅 워커까지 깨울 이유는 없다.
 */
public enum WorkQueue {

    /** {@code agent_runs} — 사용자 메시지로 만들어진 Agent 태스크. */
    AGENT_RUN,

    /** {@code deployment_histories} — 사용자가 누른 배포. */
    DEPLOYMENT_RUN,

    /** {@code cloud_connection_verification_jobs} — 사용자가 요청한 클라우드 권한 검증. */
    CLOUD_CONNECTION_VERIFICATION,

    /** {@code webhook_deliveries} — GitHub 이 보내온 웹훅. */
    WEBHOOK_DELIVERY
}
