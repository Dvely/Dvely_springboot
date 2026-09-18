package com.example.dvely.agent.infrastructure.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;

/**
 * 로컬 Docker 데몬에 붙는 클라이언트를 만든다.
 *
 * <p><b>{@code DockerClient} 는 스프링 빈이 아니다.</b> 빈으로 두면 Docker 가 없는 환경에서
 * 컨텍스트 로드가 통째로 실패한다(실제로 82개가 한 번에 죽은 적이 있다). 그래서 필요한 쪽이
 * 각자 만드는데, 그 부트스트랩이 세 곳에 복사돼 있었다 — 한 곳만 고치면 나머지가 다른 데몬을
 * 보게 되는 종류의 중복이라 여기로 모은다.</p>
 */
public final class DockerClients {

    private DockerClients() {
    }

    public static DockerClient createLocal() {
        String dockerHost = System.getProperty("os.name").toLowerCase().contains("win")
                ? "npipe:////./pipe/docker_engine"
                : "unix:///var/run/docker.sock";
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .build();
        var httpClient = new OkDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
