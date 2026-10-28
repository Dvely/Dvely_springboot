package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.git.ServerGitCheckout;
import com.example.dvely.agent.infrastructure.git.ServerGitProperties;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 서버 측 clone(#523 PR-3)이 넣는 표시 파일 — source(어느 저장소)와 base(#539, 넣은 커밋). */
class ServerRepositoryCheckoutTest {

    private final ServerGitCheckout gitCheckout = mock(ServerGitCheckout.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ServerRepositoryCheckout checkout = new ServerRepositoryCheckout(
            mock(ServerGitProperties.class), gitCheckout, mock(DockerContainerService.class), users,
            mock(AuthCommandService.class));

    @BeforeEach
    void setUp() {
        when(users.findById(7L)).thenReturn(Optional.of(new User(7L, new GithubId("1"), "octo", null, 1L, "gh-token",
                "refresh", LocalDateTime.now().plusHours(1))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePreviewTreeCarriesItsSourceAndTheCheckedOutCommitAsBase() {
        when(gitCheckout.checkout(anyString(), any(), any(), eq("app"), any(), any())).thenReturn(Optional.of("abc123"));

        checkout.intoPreview("c-1", 7L, "octo/app");

        ArgumentCaptor<Function<String, Map<String, byte[]>>> extras = ArgumentCaptor.forClass(Function.class);
        verify(gitCheckout).checkout(eq("https://github.com/octo/app.git"), any(), any(), eq("app"), extras.capture(), any());
        Map<String, byte[]> files = extras.getValue().apply("0123456789abcdef0123456789abcdef01234567");
        assertThat(text(files.get(".qeploy-source"))).isEqualTo("octo/app");
        assertThat(text(files.get(".qeploy-base"))).isEqualTo("0123456789abcdef0123456789abcdef01234567");
        // 빈 저장소면 EMPTY — push 검사가 "빈 저장소에서 준비했다"로 읽는다
        assertThat(text(extras.getValue().apply(null).get(".qeploy-base"))).isEqualTo("EMPTY");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBuildTreeGetsNoMarkers() {
        when(gitCheckout.checkout(anyString(), any(), any(), eq("app"), any(), any())).thenReturn(Optional.of("abc123"));

        checkout.intoBuild("c-1", 7L, "octo/app", "v3");

        ArgumentCaptor<Function<String, Map<String, byte[]>>> extras = ArgumentCaptor.forClass(Function.class);
        verify(gitCheckout).checkout(anyString(), any(), any(), eq("app"), extras.capture(), any());
        assertThat(extras.getValue().apply("abc123")).isEmpty();
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
