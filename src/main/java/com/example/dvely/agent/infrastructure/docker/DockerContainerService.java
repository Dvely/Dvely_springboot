package com.example.dvely.agent.infrastructure.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DockerContainerService {

    private static final String IMAGE          = "node:20-alpine";
    private static final int    CONTAINER_PORT = 3000;
    private static final long   EXEC_TIMEOUT_MIN = 10L;
    private static final String AGENT_LABEL = "qeploy.agent";
    private static final String USER_ID_LABEL = "qeploy.userId";
    private static final String PREVIEW_SESSION_ID_LABEL = "qeploy.previewSessionId";
    private static final String PROJECT_ID_LABEL = "qeploy.projectId";
    private static final String CONVERSATION_ID_LABEL = "qeploy.conversationId";
    private static final String TASK_ID_LABEL = "qeploy.taskId";
    private static final String LEGACY_AGENT_LABEL = "dvely.agent";

    private final DockerClient dockerClient;

    public DockerContainerService() {
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
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    DockerContainerService(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public String createAndStartContainer(Long userId,
                                          String previewSessionId,
                                          Long projectId,
                                          Long conversationId,
                                          String taskId) {
        pullImageIfNeeded();

        ExposedPort exposedPort = ExposedPort.tcp(CONTAINER_PORT);
        Ports portBindings = new Ports();
        portBindings.bind(exposedPort, Ports.Binding.bindPort(0));

        Map<String, String> labels = new HashMap<>();
        labels.put(AGENT_LABEL, "true");
        labels.put(USER_ID_LABEL, String.valueOf(userId));
        putLabel(labels, PREVIEW_SESSION_ID_LABEL, previewSessionId);
        putLabel(labels, PROJECT_ID_LABEL, projectId);
        putLabel(labels, CONVERSATION_ID_LABEL, conversationId);
        putLabel(labels, TASK_ID_LABEL, taskId);

        CreateContainerResponse container = dockerClient.createContainerCmd(IMAGE)
                .withExposedPorts(exposedPort)
                .withHostConfig(HostConfig.newHostConfig()
                        .withPortBindings(portBindings)
                        .withMemory(1024 * 1024 * 1024L))
                .withLabels(labels)
                .withCmd("tail", "-f", "/dev/null")
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        log.info("Docker 컨테이너 시작: id={} userId={}", container.getId(), userId);
        return container.getId();
    }

    public boolean isContainerRunning(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            return inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning());
        } catch (Exception exception) {
            return false;
        }
    }

    public List<com.github.dockerjava.api.model.Container> listAgentContainers() {
        Map<String, com.github.dockerjava.api.model.Container> containers = new LinkedHashMap<>();
        listContainersByLabel(AGENT_LABEL).forEach(container -> containers.put(container.getId(), container));
        listContainersByLabel(LEGACY_AGENT_LABEL).forEach(container -> containers.put(container.getId(), container));
        return List.copyOf(containers.values());
    }

    private List<com.github.dockerjava.api.model.Container> listContainersByLabel(String label) {
        return dockerClient.listContainersCmd()
                .withLabelFilter(List.of(label + "=true"))
                .exec();
    }

    public int getMappedPort(String containerId) {
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        Ports.Binding[] bindings = inspect.getNetworkSettings() == null
                || inspect.getNetworkSettings().getPorts() == null
                || inspect.getNetworkSettings().getPorts().getBindings() == null
                ? null
                : inspect.getNetworkSettings().getPorts()
                .getBindings()
                .get(ExposedPort.tcp(CONTAINER_PORT));
        if (bindings == null
                || bindings.length == 0
                || bindings[0] == null
                || bindings[0].getHostPortSpec() == null
                || bindings[0].getHostPortSpec().isBlank()) {
            throw new IllegalStateException("컨테이너 포트 바인딩이 없습니다. containerId=" + containerId);
        }
        return Integer.parseInt(bindings[0].getHostPortSpec());
    }

    public String exec(String containerId, String command) {
        log.debug("Docker exec: {}", command);
        ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd("sh", "-c", command)
                .exec();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        try {
            dockerClient.execStartCmd(execCreate.getId())
                    .withDetach(false)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (frame.getStreamType() == StreamType.STDOUT) {
                                try { stdout.write(frame.getPayload()); } catch (Exception ignored) {}
                            } else if (frame.getStreamType() == StreamType.STDERR) {
                                try { stderr.write(frame.getPayload()); } catch (Exception ignored) {}
                            }
                        }
                    })
                    .awaitCompletion(EXEC_TIMEOUT_MIN, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Docker exec 인터럽트: " + command, e);
        }

        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        return out + (err.isBlank() ? "" : "\n[STDERR]\n" + err);
    }

    public void removeContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
        } catch (NotFoundException e) {
            log.info("Docker 컨테이너가 이미 없습니다. stop 생략: id={}", containerId);
            return;
        } catch (NotModifiedException e) {
            log.info("Docker 컨테이너가 이미 중지되어 있습니다. remove 진행: id={}", containerId);
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (NotFoundException e) {
            log.info("Docker 컨테이너가 이미 없습니다. remove 생략: id={}", containerId);
            return;
        }
        log.info("Docker 컨테이너 제거: id={}", containerId);
    }

    private void pullImageIfNeeded() {
        try {
            dockerClient.pullImageCmd(IMAGE).start().awaitCompletion(3, TimeUnit.MINUTES);
            log.info("Docker 이미지 준비 완료: {}", IMAGE);
        } catch (Exception e) {
            log.warn("이미지 pull 실패 (로컬에 존재할 수 있음): {}", e.getMessage());
        }
    }

    private void putLabel(Map<String, String> labels, String key, Object value) {
        if (value != null) {
            labels.put(key, String.valueOf(value));
        }
    }
}
