package com.example.dvely.provisioning.domain.value;

/**
 * EC2 백엔드를 어떤 형태로 실행할지. 산출물·실행 커맨드가 갈린다.
 *
 * <ul>
 *   <li>{@link #NATIVE} — 스택별 산출물을 인스턴스에서 직접 실행(현재: Gradle jar 를 {@code java -jar}).
 *       컨테이너 없음. 스택마다 빌드·실행이 다르다.</li>
 *   <li>{@link #DOCKER} — 앱을 Docker 이미지로 빌드해 인스턴스에서 {@code docker run}. 스택 무관
 *       (Dockerfile 만 있으면 무엇이든 같은 경로) — 다스택 지원의 핵심.</li>
 * </ul>
 */
public enum ServerDeployMode {
    NATIVE,
    DOCKER
}
