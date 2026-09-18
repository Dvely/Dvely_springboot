package com.example.dvely.preview.infrastructure.egress;

import java.util.List;

/**
 * 프리뷰 컨테이너를 <b>어느 네트워크에 붙이고 무엇을 알려줄지</b> 정한다.
 *
 * <p>구현이 둘인 이유는 두 성질이 서로 배타적이기 때문이다. 같은 네트워크를 공유하면
 * {@code enable_icc=false}(컨테이너 간 차단)와 프록시 접근이 <b>동시에 성립하지 않는다</b> —
 * icc 를 끄면 프리뷰가 프록시에도 닿지 못한다(2026-09-15 실측). 그래서 격리를 유지하면서
 * egress 를 거르려면 프리뷰마다 네트워크를 갈라야 하고, 그것은 기본 동작과 형태가 다르다.</p>
 */
public interface PreviewNetworkPolicy {

    /** 이 프리뷰가 붙을 네트워크 이름. 호출 시점에 없으면 만든다. */
    String attachNetwork(String previewSessionId);

    /** 컨테이너 환경변수. 프록시를 쓰는 구현만 값을 준다. */
    List<String> environment();

    /**
     * 세션이 끝난 뒤 그 세션 몫의 자원을 되돌린다. <b>멱등이어야 한다</b> — 회수 경로가 여럿이고
     * (만료·회수·강제 종료) 같은 세션에 두 번 불릴 수 있다.
     */
    void release(String previewSessionId);
}
