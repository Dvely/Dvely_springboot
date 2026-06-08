package com.example.dvely.agent.infrastructure.docker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserContainerRegistryTest {

    @Mock private DockerContainerService dockerService;

    @InjectMocks private UserContainerRegistry registry;

    private static final Long USER_ID = 1L;
    private static final String CONTAINER_ID = "abc123";

    @BeforeEach
    void setUp() {
        registry.register(USER_ID, new UserContainerInfo(CONTAINER_ID, 32768, Instant.now()));
    }

    @Test
    void remove_Docker_성공시_레지스트리에서_제거() {
        doNothing().when(dockerService).removeContainer(CONTAINER_ID);

        registry.remove(USER_ID);

        assertThat(registry.find(USER_ID)).isEmpty();
        verify(dockerService).removeContainer(CONTAINER_ID);
    }

    @Test
    void remove_Docker_실패시_레지스트리_유지() {
        doThrow(new RuntimeException("Docker 오류")).when(dockerService).removeContainer(CONTAINER_ID);

        registry.remove(USER_ID);

        assertThat(registry.find(USER_ID)).isPresent();
        verify(dockerService).removeContainer(CONTAINER_ID);
    }

    @Test
    void remove_없는_userId는_noop() {
        registry.remove(999L);

        verifyNoInteractions(dockerService);
    }

    @Test
    void remove_Docker_성공후_find_반환하지_않음() {
        doNothing().when(dockerService).removeContainer(CONTAINER_ID);

        registry.remove(USER_ID);

        Optional<UserContainerInfo> result = registry.find(USER_ID);
        assertThat(result).isEmpty();
    }
}
