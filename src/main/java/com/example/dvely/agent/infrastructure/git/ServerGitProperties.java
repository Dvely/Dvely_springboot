package com.example.dvely.agent.infrastructure.git;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 서버 측 git 작업(#523) 설정. 단계별 플래그는 기본 꺼짐이다 — 켜기 전까지 기존 컨테이너 경로가 그대로 돈다.
 *
 * @param pushEnabled preview push·diff 를 서버로 옮긴 경로를 쓸지(PR-2)
 * @param cloneEnabled clone·fetch 를 서버로 옮긴 경로를 쓸지(PR-3)
 * @param maxFiles    스냅샷 파일 수 상한(.git·node_modules 를 뺀 뒤)
 * @param maxBytes    스냅샷 총 바이트 상한
 * @param workDir     작업 단위 임시 저장소를 만들 위치
 */
@ConfigurationProperties("qeploy.git.server-side")
public record ServerGitProperties(
        @DefaultValue("false") boolean pushEnabled,
        @DefaultValue("false") boolean cloneEnabled,
        @DefaultValue("20000") int maxFiles,
        @DefaultValue("209715200") long maxBytes,
        @DefaultValue("${java.io.tmpdir}/qeploy-git") String workDir
) {
}
