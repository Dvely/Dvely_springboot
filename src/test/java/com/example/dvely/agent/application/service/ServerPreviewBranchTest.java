package com.example.dvely.agent.application.service;

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
    private final ServerPreviewBranch branch = new ServerPreviewBranch(
            mock(ServerGitProperties.class), publisher, mock(DockerContainerService.class),
            mock(UserRepository.class), mock(AuthCommandService.class), ledger);

    @Test
    void aPushedCommitIsRecordedAsThisTasks() {
        ObjectId commit = ObjectId.fromString("0123456789abcdef0123456789abcdef01234567");
        when(publisher.publish(any(), any(), anyBoolean(), any())).thenReturn(new Published(Optional.of(commit), commit));

        branch.publish("c", "t", "u", "octo/app", "task-1", false, Optional.empty(), false);

        verify(ledger).recordTask("octo/app", commit.name(), "task-1");
    }

    @Test
    void nothingIsRecordedWhenNothingWasPushed() {
        ObjectId tree = ObjectId.fromString("0123456789abcdef0123456789abcdef01234567");
        when(publisher.publish(any(), any(), anyBoolean(), any())).thenReturn(new Published(Optional.empty(), tree));

        branch.publish("c", "t", "u", "octo/app", "task-1", false, Optional.empty(), false);

        verifyNoInteractions(ledger);
    }

    @Test
    void anExistingRepositoryGetsTheReviewGuidance() {
        when(publisher.publish(any(), any(), anyBoolean(), any()))
                .thenThrow(new ProtectedChangeRequiresApprovalException(List.of(".github/workflows/ci.yml")));

        assertThatThrownBy(() -> branch.publish("c", "t", "u", "octo/app", "task-1", false, Optional.empty(), false))
                .hasMessageContaining(".github/workflows/ci.yml")
                .hasMessageContaining("결과 승인 단계에서 변경 내역을 확인한 뒤 반영됩니다");
    }

    @Test
    void aBrandNewRepositoryGetsGuidanceItCanActuallyFollow() {
        when(publisher.publish(any(), any(), anyBoolean(), any()))
                .thenThrow(new ProtectedChangeRequiresApprovalException(List.of(".github/workflows/ci.yml")));

        assertThatThrownBy(() -> branch.publish("c", "t", "u", "octo/app", "task-1", true, Optional.empty(), false))
                .hasMessageContaining("새 저장소에 처음 올리는 결과에는 .github 아래 파일을 넣을 수 없습니다");
    }
}
