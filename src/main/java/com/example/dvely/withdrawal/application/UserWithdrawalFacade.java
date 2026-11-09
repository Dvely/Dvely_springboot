package com.example.dvely.withdrawal.application;

import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.audit.application.AuditEvent;
import com.example.dvely.audit.application.AuditRecorder;
import com.example.dvely.audit.domain.value.AuditAction;
import com.example.dvely.audit.domain.value.AuditActorType;
import com.example.dvely.audit.domain.value.AuditOutcome;
import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.application.port.out.TokenClaims;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회원 탈퇴 요청 하나를 끝까지 처리한다(#613 PR-2). {@link UserWithdrawalService} 가 DB 의 행을 지우고, 이 클래스는 그 앞뒤를
 * 맡는다 — 돌고 있는 일을 멈추고, 토큰을 폐기하고, 탈퇴 사실을 감사에 남긴다.
 *
 * <p><b>순서가 요점이다.</b></p>
 * <ol>
 *   <li><b>살아 있는 자원부터 본다.</b> 있으면 409 로 돌려보내고 아무것도 건드리지 않는다. 막힐 탈퇴 때문에 사용자의 작업을
 *       취소하거나 프리뷰를 닫지 않기 위해서다. (삭제 트랜잭션이 잠금을 쥔 채 한 번 더 본다 — 여기는 빠른 거절일 뿐이다.)</li>
 *   <li><b>끝나지 않은 에이전트 런을 취소한다.</b> 행을 지운 <b>뒤</b>에는 할 수 없다. 실행기는 단계마다 취소 여부를 읽는데, 런이
 *       없으면 "취소되지 않음" 으로 읽고 다음 단계로 간다.</li>
 *   <li><b>컨테이너를 지운다.</b> 사용자 라벨이 붙은 프리뷰·빌드 컨테이너와, 라벨이 없는 프리뷰 테스트 DB 컨테이너다. 행을 지운
 *       뒤에는 회수 워커가 이것들을 찾을 길이 없다(세션·DB 행이 곧 회수의 근거다).</li>
 *   <li><b>한 트랜잭션에서 행을 지우고 지금 쓰는 access 토큰을 폐기한다.</b> 둘 중 하나만 되는 일이 없다. refresh 토큰은 행
 *       삭제에 포함된다.</li>
 *   <li><b>라벨로 한 번 더 지운다.</b> 2~4 사이에 실행기가 띄운 컨테이너를 받아낸다.</li>
 *   <li><b>탈퇴 사실을 감사에 남긴다.</b> 행 삭제가 그 사용자의 감사 기록까지 지우므로 커밋 뒤에 쓴다.</li>
 * </ol>
 *
 * <p>도커 정리는 최선 노력이다. 도커에 닿지 못해도 탈퇴(행 삭제·토큰 폐기)는 진행한다 — 처리방침이 약속한 파기는 DB 의
 * 개인정보이고, 남은 컨테이너는 게이트웨이가 찾을 세션 행이 없어 밖에서 닿지 않는다. 대신 운영자가 지울 수 있도록 회원 번호와
 * 라벨을 WARN 으로 남긴다.</p>
 *
 * <p>트랜잭션을 걸지 않는다 — 도커 호출을 트랜잭션 안에 두면 그동안 커넥션을 붙든다(#337). 4번만 트랜잭션이다.</p>
 */
@Slf4j
@Service
public class UserWithdrawalFacade {

    private final UserWithdrawalService withdrawalService;
    private final AgentOrchestrator agentOrchestrator;
    private final DockerContainerService dockerService;
    private final TokenBlacklistPort tokenBlacklistPort;
    private final AuditRecorder auditRecorder;
    private final TransactionTemplate transaction;

    public UserWithdrawalFacade(UserWithdrawalService withdrawalService,
                                AgentOrchestrator agentOrchestrator,
                                DockerContainerService dockerService,
                                TokenBlacklistPort tokenBlacklistPort,
                                AuditRecorder auditRecorder,
                                PlatformTransactionManager transactionManager) {
        this.withdrawalService = withdrawalService;
        this.agentOrchestrator = agentOrchestrator;
        this.dockerService = dockerService;
        this.tokenBlacklistPort = tokenBlacklistPort;
        this.auditRecorder = auditRecorder;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** 탈퇴를 막는 자원이 있는지 미리 본다(화면 안내용). */
    public List<LiveResource> findLiveResources(long userId) {
        return withdrawalService.findLiveResources(userId);
    }

    /**
     * 탈퇴한다. 이미 탈퇴했으면 아무것도 하지 않는다.
     *
     * @param userId        탈퇴하는 사용자
     * @param currentAccess 이 요청을 보낸 access 토큰. 함께 폐기한다
     * @throws WithdrawalBlockedException 사용자 AWS 계정에 살아 있는 자원이 남아 있을 때. 아무것도 지우지도 멈추지도 않았다
     */
    public void withdraw(long userId, TokenClaims currentAccess) {
        List<LiveResource> live = withdrawalService.findLiveResources(userId);
        if (!live.isEmpty()) {
            throw new WithdrawalBlockedException(live);
        }

        for (String taskId : withdrawalService.findUnfinishedTaskIds(userId)) {
            agentOrchestrator.cancel(taskId, userId);
        }
        List<String> localDatabases = withdrawalService.findLocalDatabaseContainerIds(userId);
        removeContainers(userId);
        bestEffort(userId, "프리뷰 DB 컨테이너", localDatabases, dockerService::removeDatabaseContainerWithNetwork);

        Boolean withdrawn = transaction.execute(status -> {
            boolean deleted = withdrawalService.withdraw(userId);
            // 이미 탈퇴했으면(같은 토큰으로 겹친 요청의 뒤쪽) 앞선 요청이 이 토큰을 이미 폐기했다 — 다시 넣으면 jti 중복이다.
            if (deleted) {
                tokenBlacklistPort.revoke(currentAccess.jti(), currentAccess.expiresAt());
            }
            return deleted;
        });
        if (!Boolean.TRUE.equals(withdrawn)) {
            return;
        }

        removeContainers(userId);
        auditRecorder.record(new AuditEvent(AuditAction.USER_WITHDRAWN, AuditOutcome.SUCCEEDED, AuditActorType.USER,
                userId, null, null, null, null, null, null, null));
    }

    private void removeContainers(long userId) {
        List<String> containers;
        try {
            containers = dockerService.listContainerIdsOwnedBy(userId);
        } catch (RuntimeException e) {
            log.warn("[Withdrawal] 컨테이너 목록을 읽지 못해 정리를 건너뜀 — 운영자가 label qeploy.userId={} 로 지워야 한다: {}",
                    userId, e.toString());
            return;
        }
        bestEffort(userId, "컨테이너", containers, dockerService::removeContainer);
    }

    private void bestEffort(long userId, String what, List<String> containerIds, Consumer<String> remove) {
        for (String containerId : containerIds) {
            try {
                remove.accept(containerId);
            } catch (RuntimeException e) {
                log.warn("[Withdrawal] {} 제거 실패 — 운영자가 지워야 한다: userId={} containerId={} {}",
                        what, userId, containerId, e.toString());
            }
        }
    }
}
