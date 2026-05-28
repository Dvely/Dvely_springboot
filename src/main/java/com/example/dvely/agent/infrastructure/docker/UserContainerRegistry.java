package com.example.dvely.agent.infrastructure.docker;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserContainerRegistry {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final DockerContainerService                      dockerService;
    private final ConcurrentHashMap<Long, UserContainerInfo> registry = new ConcurrentHashMap<>();

    @PostConstruct
    public void restoreFromDocker() {
        try {
            List<Container> containers = dockerService.listAgentContainers();
            for (Container c : containers) {
                String userIdStr = c.getLabels() == null ? null : c.getLabels().get("dvely.userId");
                if (userIdStr == null) continue;
                Long userId = Long.parseLong(userIdStr);
                Integer hostPort = Arrays.stream(c.getPorts())
                        .filter(p -> p.getPrivatePort() != null && p.getPrivatePort() == 3000 && p.getPublicPort() != null)
                        .map(ContainerPort::getPublicPort)
                        .findFirst().orElse(null);
                if (hostPort != null) {
                    registry.put(userId, new UserContainerInfo(c.getId(), hostPort, Instant.now()));
                    log.info("[ContainerRegistry] 복구: userId={} containerId={} port={}", userId, c.getId(), hostPort);
                }
            }
            log.info("[ContainerRegistry] 서버 재시작 후 {}개 컨테이너 복구 완료", registry.size());
        } catch (Exception e) {
            log.warn("[ContainerRegistry] Docker에 연결할 수 없어 컨테이너 복구를 건너뜁니다: {}", e.getMessage());
        }
    }

    public Optional<UserContainerInfo> find(Long userId) {
        return Optional.ofNullable(registry.get(userId));
    }

    public void register(Long userId, UserContainerInfo info) {
        registry.put(userId, info);
        log.info("[ContainerRegistry] 등록: userId={} containerId={} port={}",
                userId, info.containerId(), info.previewPort());
    }

    public void touch(Long userId) {
        registry.computeIfPresent(userId, (k, info) -> info.touch());
    }

    public void remove(Long userId) {
        UserContainerInfo info = registry.remove(userId);
        if (info != null) {
            dockerService.removeContainer(info.containerId());
            log.info("[ContainerRegistry] 제거: userId={} containerId={}", userId, info.containerId());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        registry.forEach((userId, info) -> {
            if (info.isExpired(TTL)) {
                log.info("[ContainerRegistry] TTL 만료 제거: userId={} containerId={}", userId, info.containerId());
                remove(userId);
            }
        });
    }
}
