package com.example.dvely.change.application.service;

import com.example.dvely.change.infrastructure.persistence.entity.ServerPushedCommitEntity;
import com.example.dvely.change.infrastructure.persistence.repository.SpringDataServerPushedCommitRepository;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 서버 측 git 이 push 한 커밋을 남긴다(#532). 반영 범위는 "이번 작업의 커밋"·"거절 REVERT 커밋"을 이 기록으로만
 * 판정한다 — 커밋 메시지는 저장소에 push 할 수 있는 누구나 꾸밀 수 있다.
 *
 * <p>기록은 보조 경로다. 실패해도 push 흐름을 막지 않는다. 빠지면 그 커밋이 "출처를 알 수 없음"으로 보여 경고가
 * 커지는 쪽으로만 틀린다. 별도 트랜잭션으로 남긴다 — push 는 이미 일어났으니 호출한 쪽이 롤백돼도 기록은 맞다.
 * 커밋까지 이 안에서 끝나므로 커밋 실패도 여기서 잡힌다.</p>
 */
@Slf4j
@Component
public class ServerPushLedger {

    private final SpringDataServerPushedCommitRepository repository;
    private final TransactionTemplate requiresNew;

    public ServerPushLedger(SpringDataServerPushedCommitRepository repository,
                            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void recordTask(String repositoryFullName, String commitSha, String taskId) {
        record(new ServerPushedCommitEntity(commitSha, repositoryFullName, taskId, ServerPushedCommitEntity.TASK));
    }

    public void recordRevert(String repositoryFullName, String commitSha, String taskId) {
        record(new ServerPushedCommitEntity(commitSha, repositoryFullName, taskId, ServerPushedCommitEntity.REVERT));
    }

    public List<ServerPushedCommitEntity> find(String repositoryFullName, Collection<String> commitShas) {
        if (commitShas.isEmpty()) {
            return List.of();
        }
        return repository.findByRepositoryAndCommitShaIn(repositoryFullName, commitShas);
    }

    private void record(ServerPushedCommitEntity entity) {
        try {
            requiresNew.executeWithoutResult(status -> repository.save(entity));
        } catch (RuntimeException e) {
            log.warn("[ServerPushLedger] push 기록 실패 | kind={} taskId={} cause={}",
                    entity.getKind(), entity.getTaskId(), e.getClass().getSimpleName());
        }
    }
}
