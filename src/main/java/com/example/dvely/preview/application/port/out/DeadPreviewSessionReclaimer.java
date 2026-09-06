package com.example.dvely.preview.application.port.out;

/**
 * 컨테이너는 살아있으나 <b>안쪽 앱 프로세스가 죽어</b> 무응답인 ACTIVE 프리뷰 세션을 회수하는 능력.
 *
 * <p>게이트웨이({@code PreviewGatewayService})가 프록시 도달 실패를 확인했을 때 호출한다. 이 케이스는
 * attach/{@code findCurrent} 의 컨테이너-생존 확인({@code isContainerRunning})만으로는 걸러지지 않는다 —
 * 컨테이너는 떠 있으므로 살아있다고 보이지만 그 안의 서버는 죽어 502 만 나온다. 회수(EXPIRED + 컨테이너
 * 제거)하면 {@code findCurrent} 가 "없음"으로 답해 FE 가 새 빌드 CTA 로 자동 복귀시킨다.</p>
 *
 * <p>게이트웨이만 이 판정을 내리는 이유는 <b>host-affinity</b> 다: 프리뷰 컨테이너는 특정 호스트의 로컬
 * Docker 이고 게이트웨이는 {@code 127.0.0.1:hostPort} 로 도달하므로, 그 컨테이너를 띄운 호스트의 게이트웨이만
 * 도달성을 옳게 관찰한다. 다른 인스턴스가 원격으로 프로브하면 무조건 실패해 살아있는 세션을 오탐 회수한다 —
 * 그래서 백그라운드 워커가 아니라 게이트웨이가 이 포트를 호출한다.</p>
 */
public interface DeadPreviewSessionReclaimer {

    /**
     * ACTIVE 세션을 EXPIRED 로 닫고 컨테이너를 회수한다. 이미 ACTIVE 가 아니면(다른 요청이 방금 회수했거나
     * 만료됐으면) 아무것도 하지 않는다.
     *
     * @return 이 호출이 실제로 회수했으면 true
     */
    boolean reclaimUnreachable(String sessionId);
}
