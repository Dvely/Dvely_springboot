package com.example.dvely.provisioning.domain.value;

/**
 * 웹(프론트) 컨테이너 배포 스펙. DOCKER 배포에서 같은 EC2 에 프론트 nginx 컨테이너를 함께 띄울 때 쓴다
 * (back+web+db "올인원", 같은 오리진 → CORS 불필요).
 *
 * @param frontendRepo  split — 별도 프론트 저장소(owner/repo). null 이면 모노(백엔드 레포에서 가져온다).
 * @param frontendDir   모노 — 프론트가 있는 하위폴더(백엔드 레포 기준). split 에서도 하위폴더 지정에 쓸 수 있다.
 * @param apiPathPrefix 백엔드 API 프리픽스(nginx 가 app 으로 프록시). 비면 {@code /api}. 콤마로 여러 개 가능.
 */
public record WebFrontendSpec(String frontendRepo, String frontendDir, String apiPathPrefix) {

    /** 웹 컨테이너를 쓰는가 — 프론트 소스(레포 또는 하위폴더)가 하나라도 지정됐는지. */
    public boolean hasWeb() {
        return notBlank(frontendRepo) || notBlank(frontendDir);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
