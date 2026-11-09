package com.example.dvely.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditCategory;
import com.example.dvely.audit.domain.value.AuditOutcome;
import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.application.port.out.TokenClaims;
import com.example.dvely.withdrawal.application.LiveResource;
import com.example.dvely.withdrawal.application.UserWithdrawalFacade;
import com.example.dvely.withdrawal.application.UserWithdrawalService;
import com.example.dvely.withdrawal.application.WithdrawalBlockedException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 탈퇴 요청의 앞뒤 처리(#613 PR-2). 무엇을 어떤 순서로 하는지, 그리고 무엇이 실패해도 무엇은 지켜지는지를 고정한다.
 * 행 삭제 자체는 {@link UserWithdrawalServiceTest} 가, 엔드포인트 전체는 {@link UserWithdrawalEndpointTest} 가 실 DB 로 본다.
 */
class UserWithdrawalFacadeTest {

    private static final long USER = 7L;
    private static final TokenClaims ACCESS = new TokenClaims(USER, "jti-1", LocalDateTime.of(2026, 11, 9, 13, 0));
    private static final LiveResource SERVER = new LiveResource(LiveResource.Type.SERVER, 3, 11L, "i-0abc", "RUNNING");

    private final UserWithdrawalService service = mock(UserWithdrawalService.class);
    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final DockerContainerService docker = mock(DockerContainerService.class);
    private final TokenBlacklistPort blacklist = mock(TokenBlacklistPort.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = new SimpleTransactionStatus();

    private UserWithdrawalFacade facade;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(service.findLiveResources(USER)).thenReturn(List.of());
        when(service.findUnfinishedTaskIds(USER)).thenReturn(List.of("t-1", "t-2"));
        when(service.findLocalDatabaseContainerIds(USER)).thenReturn(List.of("db-1"));
        when(docker.listContainerIdsOwnedBy(USER)).thenReturn(List.of("c-1"), List.of("c-late"));
        when(service.withdraw(USER)).thenReturn(true);
        facade = new UserWithdrawalFacade(service, orchestrator, docker, blacklist, audit, transactionManager);
    }

    @Test
    void stopsTheWorkThenDeletesAndRevokesInOneTransactionThenSweepsAgainAndAudits() {
        facade.withdraw(USER, ACCESS);

        InOrder order = inOrder(service, orchestrator, docker, transactionManager, blacklist, audit);
        order.verify(service).findLiveResources(USER);
        // 행을 지우기 전에 취소한다 — 지운 뒤에는 실행기가 "런 없음" 을 "취소 안 됨" 으로 읽는다
        order.verify(orchestrator).cancel("t-1", USER);
        order.verify(orchestrator).cancel("t-2", USER);
        order.verify(docker).removeContainer("c-1");
        order.verify(docker).removeDatabaseContainerWithNetwork("db-1");
        order.verify(transactionManager).getTransaction(any());
        order.verify(service).withdraw(USER);
        order.verify(blacklist).revoke("jti-1", ACCESS.expiresAt());
        order.verify(transactionManager).commit(transactionStatus);
        // 삭제하는 사이 실행기가 띄운 것을 받아낸다
        order.verify(docker).removeContainer("c-late");
        order.verify(audit).record(any());
    }

    @Test
    void auditsOnlyTheFactOfWithdrawalWithTheMemberNumber() {
        facade.withdraw(USER, ACCESS);

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue()).isEqualTo(new AuditEvent(AuditAction.USER_WITHDRAWN, AuditOutcome.SUCCEEDED,
                AuditActorType.USER, USER, null, null, null, null, null, null, null));
        assertThat(AuditAction.USER_WITHDRAWN.category()).isEqualTo(AuditCategory.ACCOUNT);
    }

    @Test
    void liveResourcesRefuseBeforeTouchingAnything() {
        when(service.findLiveResources(USER)).thenReturn(List.of(SERVER));

        assertThatThrownBy(() -> facade.withdraw(USER, ACCESS))
                .isInstanceOfSatisfying(WithdrawalBlockedException.class,
                        e -> assertThat(e.liveResources()).containsExactly(SERVER));

        verify(orchestrator, never()).cancel(anyString(), anyLong());
        verify(docker, never()).listContainerIdsOwnedBy(anyLong());
        verify(service, never()).withdraw(anyLong());
        verify(blacklist, never()).revoke(any(), any());
        verify(audit, never()).record(any());
    }

    @Test
    void aResourceThatAppearsBeforeTheLockRollsBackWithoutRevokingOrAuditing() {
        // 사전 확인은 비었는데 잠금을 쥔 뒤 다시 보니 생겼다(그 사이 배포가 시작됐다)
        when(service.withdraw(USER)).thenThrow(new WithdrawalBlockedException(List.of(SERVER)));

        assertThatThrownBy(() -> facade.withdraw(USER, ACCESS)).isInstanceOf(WithdrawalBlockedException.class);

        verify(transactionManager).rollback(transactionStatus);
        verify(blacklist, never()).revoke(any(), any());
        verify(audit, never()).record(any());
    }

    @Test
    void dockerTroubleDoesNotStopTheWithdrawal() {
        when(docker.listContainerIdsOwnedBy(USER)).thenReturn(List.of("c-1", "c-2"))
                .thenThrow(new IllegalStateException("docker down"));
        doThrow(new IllegalStateException("gone")).when(docker).removeContainer("c-1");
        doThrow(new IllegalStateException("gone")).when(docker).removeDatabaseContainerWithNetwork("db-1");

        facade.withdraw(USER, ACCESS);

        verify(docker).removeContainer("c-2");   // 하나가 실패해도 나머지는 지운다
        verify(service).withdraw(USER);
        verify(blacklist).revoke("jti-1", ACCESS.expiresAt());
        verify(audit).record(any());
    }

    @Test
    void anAlreadyWithdrawnUserIsANoOpWithoutASecondRevocation() {
        // 같은 토큰으로 겹친 요청의 뒤쪽 — 앞선 요청이 지우고 이 jti 를 이미 폐기했다
        when(service.withdraw(USER)).thenReturn(false);

        facade.withdraw(USER, ACCESS);

        verify(blacklist, never()).revoke(any(), any());
        verify(audit, never()).record(any());
        verify(docker, times(1)).listContainerIdsOwnedBy(USER);
    }
}
