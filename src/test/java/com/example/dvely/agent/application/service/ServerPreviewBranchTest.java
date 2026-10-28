package com.example.dvely.agent.application.service;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import org.eclipse.jgit.lib.ObjectId;
import com.example.dvely.agent.infrastructure.git.ServerGitPublisher.Published;
import com.example.dvely.change.application.service.ServerPushLedger;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.git.ServerGitProperties;
import com.example.dvely.agent.infrastructure.git.ServerGitPublisher;
import com.example.dvely.agent.infrastructure.git.ServerGitPublisher.ProtectedChangeRequiresApprovalException;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.auth.domain.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 보호 경로로 반영이 멈췄을 때 사용자가 따를 수 있는 안내가 붙는지(배포·저장소 연결 경로). */
class ServerPreviewBranchTest {

    private final ServerGitPublisher publisher = mock(ServerGitPublisher.class);
    private final ServerPushLedger ledger = mock(ServerPushLedger.class);
    private final DockerContainerService docker = mock(DockerContainerService.class);
    private final ServerPreviewBranch branch = new ServerPreviewBranch(
            mock(ServerGitProperties.class), publisher, docker,
            mock(UserRepository.class), mock(AuthCommandService.class), ledger);

    @org.junit.jupiter.api.BeforeEach
    void noMarkerByDefault() {
        when(docker.exec(any(), any())).thenReturn("");
    }

    // ---------------------------------------------------------------- 준비 시점 base(#523)

    @Test
    void theRecordedBaseIsPassedAndAdvancedToThePushedCommit() {
        ObjectId prepared = ObjectId.fromString("1111111111111111111111111111111111111111");
        ObjectId pushed = ObjectId.fromString("2222222222222222222222222222222222222222");
        when(docker.exec(eq("c"), contains(".qeploy-base 2>/dev/null"))).thenReturn(prepared.name() + "\n");
        when(publisher.publish(any(), any(), anyBoolean(), any(), eq(ServerGitPublisher.ExpectedBase.of(prepared))))
                .thenReturn(new Published(Optional.of(pushed), pushed));

        branch.publish("c", "t", "u", "octo/app", "task-1", false, Optional.empty(), false);

        // 같은 작업의 다음 push 는 방금 올린 커밋을 base 로 본다
        verify(docker).exec("c", "printf %s " + pushed.name() + " > /workspace/.qeploy-base");
    }

    @Test
    void markersAreReadAsKnownEmptyOrUnknown() {
        when(docker.exec(eq("c"), contains(".qeploy-base"))).thenReturn("EMPTY");
        org.assertj.core.api.Assertions.assertThat(branch.expectedBase("c"))
                .isEqualTo(ServerGitPublisher.ExpectedBase.emptyRepository());
        when(docker.exec(eq("c"), contains(".qeploy-base"))).thenReturn("");
        org.assertj.core.api.Assertions.assertThat(branch.expectedBase("c")).isEqualTo(ServerGitPublisher.ExpectedBase.UNKNOWN);
        when(docker.exec(eq("c"), contains(".qeploy-base"))).thenReturn("garbage; rm -rf /");
        org.assertj.core.api.Assertions.assertThat(branch.expectedBase("c")).isEqualTo(ServerGitPublisher.ExpectedBase.UNKNOWN);
    }

    @Test
    void aMovedRemoteMakesTheNextPreparationFetchAgain() {
        when(publisher.publish(any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new ServerGitPublisher.RemoteMovedException("preview"));

        assertThatThrownBy(() -> branch.publish("c", "t", "u", "octo/app", "task-1", false, Optional.empty(), false))
                .isInstanceOf(ServerGitPublisher.RemoteMovedException.class);
        verify(docker).exec("c", "rm -f /workspace/.qeploy-source");
        verifyNoInteractions(ledger);
    }

    @Test
    void aPushedCommitIsRecordedAsThisTasks() {
        ObjectId commit = ObjectId.fromString("0123456789abcdef0123456789abcdef01234567");
        when(publisher.publish(any(), any(), anyBoolean(), any(), any())).thenReturn(new Published(Optional.of(commit), commit));

        branch.publish("c", "t", "u", "octo/app", "task-1", false, Optional.empty(), false);

        verify(ledger).recordTask("octo/app", commit.name(), "task-1");
    }

    @Test
    void nothingIsRecordedWhenNothingWasPushed() {
        ObjectId tree = ObjectId.fromString("0123456789abcdef0123456789abcdef01234567");
        when(publisher.publish(any(), any(), anyBoolean(), any(), any())).thenReturn(new Published(Optional.empty(), tree));

        branch.publish("c", "t", "u", "octo/app", "task-1", false, Optional.empty(), false);

        verifyNoInteractions(ledger);
    }

    @Test
    void anExistingRepositoryGetsTheReviewGuidance() {
        when(publisher.publish(any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new ProtectedChangeRequiresApprovalException(List.of(".github/workflows/ci.yml")));

        assertThatThrownBy(() -> branch.publish("c", "t", "u", "octo/app", "task-1", false, Optional.empty(), false))
                .hasMessageContaining(".github/workflows/ci.yml")
                .hasMessageContaining("결과 승인 단계에서 변경 내역을 확인한 뒤 반영됩니다");
    }

    @Test
    void aBrandNewRepositoryGetsGuidanceItCanActuallyFollow() {
        when(publisher.publish(any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new ProtectedChangeRequiresApprovalException(List.of(".github/workflows/ci.yml")));

        assertThatThrownBy(() -> branch.publish("c", "t", "u", "octo/app", "task-1", true, Optional.empty(), false))
                .hasMessageContaining("새 저장소에 처음 올리는 결과에는 .github 아래 파일을 넣을 수 없습니다");
    }
}
