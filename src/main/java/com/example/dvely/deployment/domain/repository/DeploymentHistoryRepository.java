package com.example.dvely.deployment.domain.repository;

import com.example.dvely.deployment.domain.model.DeploymentHistory;
import com.example.dvely.project.domain.value.DeployStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeploymentHistoryRepository {

    DeploymentHistory save(DeploymentHistory history);

    Optional<DeploymentHistory> findById(Long id);

    /** U6 6-2: 이력 목록 응답에 실제로 나가는 컬럼만 읽는다. {@link DeploymentHistoryListView} 참고. */
    List<DeploymentHistoryListView> findHistoryListViews(Long projectId);

    /** U6 6-2: version_label 이 있는 이력만. 버전 목록 응답 전용. */
    List<DeploymentVersionView> findLabeledVersionViews(Long projectId);

    /** U6 6-2: version_label 이 있고 LIVE 인 이력만. 배포 후보 응답 전용. */
    List<DeploymentVersionView> findLiveLabeledVersionViews(Long projectId);

    /**
     * U6 6-2: 가장 최근 LIVE 이력의 deployedUrl. 값이 null/공백일 수 있고, 그때 다음 LIVE 로 넘어가지
     * 않는 것이 기존 동작이라 그대로 돌려준다(빈 Optional 과 "빈 값" 을 구분하지 않는다).
     */
    Optional<String> findLatestLiveDeployedUrl(Long projectId);

    Optional<DeploymentHistory> findLatestByProjectId(Long projectId);

    List<DeploymentHistory> findByProjectIdAndStatus(Long projectId, DeployStatus status);

    Optional<DeploymentHistory> findByWorkflowRunId(Long workflowRunId);

    Optional<DeploymentHistory> findByCorrelationId(String correlationId);

    /**
     * 워크플로를 띄워놓고 결과 웹훅을 못 받아 IN_PROGRESS 에 멈춘 이력.
     *
     * 리스가 없다는 것(leaseUntil is null)이 판별 기준이다. 워커가 붙들고 준비 중인 이력은
     * 리스를 갖고 있고 그 만료는 recoverExpiredLeases 가 따로 회수한다. markDispatched 는
     * 리스를 비우므로, 리스 없이 IN_PROGRESS 인 것은 GitHub 의 답만 기다리는 상태다.
     */
    List<DeploymentHistory> findDispatchedAwaitingOutcome(LocalDateTime updatedBefore, int limit);

    List<Long> claimPending(String workerId, int limit);

    /**
     * 폴링 한 번이 하는 일 전부 — 만료 리스 회수와 claim 을 <b>한 트랜잭션</b>으로 묶는다(#340 5-1).
     * 따로 부르면 폴링 한 번이 트랜잭션 두 개가 되고, 트랜잭션마다 붙는 {@code SET autocommit} ·
     * {@code COMMIT} 의례가 유휴 DB 비용의 대부분이었다. 회수 UPDATE 가 같은 트랜잭션에서 먼저
     * 반영되므로, 방금 회수된 행을 같은 폴링의 claim 이 곧바로 집는다.
     */
    List<Long> recoverAndClaimPending(String workerId, int limit);

    void recoverExpiredLeases();

    /**
     * claim 해 놓고 실행기에 넘기지 못한 이력을 PENDING 으로 되돌린다(#340 5-2).
     *
     * 이 호출이 없으면 배치의 두 번째 이력이 IN_PROGRESS 인 채 리스 만료(2분)까지 방치된다 —
     * 사용자 화면에는 "배포 중"으로 보이지만 그것을 실제로 돌리는 스레드는 어디에도 없다.
     *
     * @return 이 호출이 실제로 되돌렸으면 true. false 는 그 사이 다른 주체(리스 만료 회수 등)가
     *         이미 이 행을 IN_PROGRESS 밖으로 옮겼다는 뜻이고, 그것도 정상이다.
     */
    boolean releaseClaim(Long historyId, String workerId, long backoffMillis);

    /**
     * 이 인스턴스가 실제로 실행 중인 이력들의 리스만 연장한다(#340 5-8).
     *
     * <p>{@code historyIds} 는 호출자의 실행 레지스트리 스냅샷이다. 비어 있으면 호출자가 아예
     * 부르지 않는다 — 실행 중인 배포가 없는데 0행짜리 UPDATE 를 30초마다 내보낼 이유가 없다.</p>
     */
    void renewLeases(String workerId, Collection<Long> historyIds);
}
