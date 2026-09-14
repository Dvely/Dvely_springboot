package com.example.dvely.agent.infrastructure.docker;

/**
 * 컨테이너를 무엇에 쓰는지. 생성 시점에 <b>반드시</b> 정한다.
 *
 * <p>둘은 같은 이미지를 쓰지만 수명도 노출면도 다르다. 그런데 한동안 같은 생성 경로를 구분 없이
 * 써서, 프리뷰 쪽 격리 정책을 손대면 배포 빌드까지 함께 흔들렸다 — 바꿀 때마다 배포 파이프라인
 * 전체를 다시 검증해야 한다는 뜻이고, 그래서 아무도 손대지 않게 된다.</p>
 *
 * <p>기본값을 두지 않는 것이 요점이다. 새 호출부가 생기면 무엇인지 고르게 강제한다 — 기본값이
 * 있으면 고르지 않은 것과 고른 것이 구분되지 않고, 잘못 고른 쪽이 조용히 돈다.</p>
 */
public enum ContainerRole {

    /**
     * 사용자에게 보여줄 결과물을 <b>서빙</b>한다. 게이트웨이가 프록시할 수 있도록 포트를 게시하고,
     * 세션이 살아 있는 동안 유지된다.
     */
    PREVIEW,

    /**
     * 저장소를 받아 <b>빌드 산출물만 꺼내고 즉시 버린다</b>. 아무것도 서빙하지 않으므로 포트를
     * 게시하지 않는다 — 게시해 봐야 아무도 연결하지 않고, 루프백이라도 열려 있는 면은 없는 편이 낫다.
     */
    BUILD;

    public boolean publishesPort() {
        return this == PREVIEW;
    }

    /** 컨테이너 라벨에 넣는 값. 운영자가 `docker ps` 에서 둘을 갈라 볼 수 있어야 한다. */
    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
