package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.service.ServerRepositoryCheckout;
import com.example.dvely.agent.infrastructure.docker.ContainerGitCredentials;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.git.ServerGitException;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;

/** 배포 빌드의 소스 받기 — 서버 측 git(#523 PR-3)이 켜지면 컨테이너 안 인증 git 을 부르지 않는다. */
class BackendSourceCloneTest {

    private final DockerContainerService docker = mock(DockerContainerService.class);
    private final ContainerGitCredentials credentials = mock(ContainerGitCredentials.class);
    private final ServerRepositoryCheckout checkout = mock(ServerRepositoryCheckout.class);
    private final BackendSourceClone clone = new BackendSourceClone(
            docker, credentials, mock(UserRepository.class), mock(AuthCommandService.class), checkout);

    @Test
    void theServerBringsTheTaggedVersionWithoutContainerCredentials() {
        when(checkout.enabled()).thenReturn(true);

        clone.cloneAt("c-1", 7L, "octo/app", "v3");

        verify(checkout).intoBuild("c-1", 7L, "octo/app", "v3");
        verify(credentials, never()).exec(anyString(), anyString(), anyString(), anyString());
        verify(docker, never()).execWithExitCode(anyString(), anyString());
        verify(docker, never()).execWithExitCode(anyString(), anyString(), any());
    }

    @Test
    void aServerSideFailureBecomesABuildFailure() {
        when(checkout.enabled()).thenReturn(true);
        when(checkout.intoBuild("c-1", 7L, "octo/app", null))
                .thenThrow(new ServerGitException("배포할 버전 태그를 저장소에서 찾지 못했습니다: v9", null));

        assertThatThrownBy(() -> clone.cloneInto("c-1", 7L, "octo/app"))
                .isInstanceOf(BackendBuildException.class)
                .hasMessageContaining("소스 가져오기 실패");
    }
}
