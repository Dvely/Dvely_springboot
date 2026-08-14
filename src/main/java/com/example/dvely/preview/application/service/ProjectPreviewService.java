package com.example.dvely.preview.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.preview.application.result.ProjectPreviewSessionResult;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 작업 지시 없이 "이 프로젝트의 현재 상태"를 프리뷰로 보여주기 위한 서비스.
 *
 * <p>{@link PreviewSessionService}가 다루는 세션은 전부 Agent CODE 스텝이 만든 것이라 taskId 가
 * 있고, 그 작업이 끝난 시점의 결과물을 보여준다. 여기서 다루는 세션은 taskId 가 없다 — 프로젝트에
 * 들어왔을 때(또는 저장소를 막 연결했을 때) preview 브랜치의 현재 내용을 그대로 clone → build →
 * serve 한 것이다. 두 종류 모두 같은 게이트웨이로 서빙되고 같은 TTL 로 회수된다.</p>
 *
 * <p>진입 경로는 두 단계로 나뉜다: 조회({@link #findCurrent})는 프로젝트 화면을 열 때마다 부르는
 * 값싼 호출이고, 생성({@link #provision})은 컨테이너 하나를 새로 띄우는 무거운 호출이라 사용자의
 * 명시적인 행동(버튼)에 묶여 있다. 이미 떠 있는 세션에 붙는 경우는 생성 쪽도 값싸게 끝난다.</p>
 *
 * <p>메서드에 {@code @Transactional}을 걸지 않은 이유는 {@link PreviewContainerOpsService}와 같다:
 * Docker 호출(컨테이너 생성·상태 조회)이 섞여 있어 트랜잭션으로 감싸면 그 시간 동안 커넥션 풀의
 * 커넥션을 붙들고 있게 된다. 개별 repository 호출은 각자의 짧은 트랜잭션으로 충분하다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectPreviewService {

    // 사용자가 "지금 이 프로젝트의 프리뷰"로 인식할 수 있는 상태들. CLOSED/EXPIRED 는 이미 끝난
    // 세션이라 현재 상태로 보여줄 것이 없다(FE 는 "없음"으로 보고 버튼을 띄우면 된다).
    private static final List<String> VISIBLE_STATUSES = List.of(
            PreviewSessionStatus.ACTIVE.name(),
            PreviewSessionStatus.PROVISIONING.name(),
            PreviewSessionStatus.FAILED.name()
    );

    // 새로 띄울지 말지를 판단할 때 기준이 되는 "살아 있는" 세션.
    private static final List<String> LIVE_STATUSES = List.of(
            PreviewSessionStatus.ACTIVE.name(),
            PreviewSessionStatus.PROVISIONING.name()
    );

    private final SpringDataPreviewSessionRepository repository;
    private final ProjectRepository projectRepository;
    private final DockerContainerService dockerService;
    private final PreviewProperties properties;
    private final ProjectPreviewProvisioner provisioner;

    /**
     * 프로젝트 화면이 열릴 때 부르는 조회. 지금 보여줄 프리뷰가 없으면 비어 있는 값을 준다.
     *
     * <p>ACTIVE 행이 있어도 컨테이너가 실제로 살아 있는지 한 번 확인한다(inspect 한 번, 수십 ms).
     * 세션 행은 컨테이너보다 오래 살 수 있다 — Docker 데몬 재시작, 외부에서의 컨테이너 정리 등으로
     * 컨테이너만 사라지면 행은 ACTIVE 인 채 남고, 그 URL 을 그대로 내려주면 FE 는 프리뷰가 뜬 줄
     * 알고 iframe 을 걸었다가 502 만 본다. 여기서 정리해 "없음"으로 답하면 사용자는 곧바로 다시
     * 띄울 수 있다.</p>
     */
    public Optional<ProjectPreviewSessionResult> findCurrent(Long projectId, Long ownerUserId) {
        requireProject(projectId, ownerUserId);

        PreviewSessionEntity session = repository
                .findFirstByProjectIdAndOwnerUserIdAndStatusInOrderByLastAccessedAtDesc(
                        projectId, ownerUserId, VISIBLE_STATUSES)
                .orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        if (isActive(session) && !dockerService.isContainerRunning(session.getContainerId())) {
            discard(session, PreviewSessionStatus.EXPIRED);
            log.info("[ProjectPreview] 컨테이너가 사라진 세션 정리: projectId={} sessionId={}",
                    projectId, session.getId());
            return Optional.empty();
        }
        return Optional.of(ProjectPreviewSessionResult.from(session));
    }

    /**
     * 살아 있으면 그 세션에 붙이고, 없으면 새로 띄운다.
     *
     * @return {@code started=false} 면 이미 서빙 중인 세션에 그대로 붙은 것(즉시 사용 가능),
     *         {@code started=true} 면 준비를 시작했거나 이미 준비 중인 것(폴링 필요)
     */
    public ProvisionOutcome provision(Long projectId, Long ownerUserId) {
        Project project = requireProject(projectId, ownerUserId);
        String sourceRepo = project.getSourceRepository();
        if (sourceRepo == null || sourceRepo.isBlank()) {
            // 현재 상태를 보여주려면 가져올 코드가 있어야 한다. 저장소 연결 전에는 Agent 가 만든
            // 작업 프리뷰만 존재할 수 있으므로, 이건 잘못된 요청이 아니라 상태 충돌(409)이다.
            throw new IllegalStateException(
                    "GitHub 저장소가 연결되지 않아 프리뷰를 띄울 수 없습니다. 저장소를 먼저 연결해주세요.");
        }

        Optional<ProvisionOutcome> attached = attachToLiveSession(projectId, ownerUserId);
        if (attached.isPresent()) {
            return attached.get();
        }

        String sessionId = UUID.randomUUID().toString();
        String accessToken = UUID.randomUUID().toString().replace("-", "");
        // conversationId/taskId 가 없는 것이 이 세션의 정의다 — 대화나 작업이 아니라 프로젝트에
        // 매달린 세션이다.
        String containerId = dockerService.createAndStartContainer(
                ownerUserId, sessionId, projectId, null, null);
        PreviewSessionEntity created = repository.save(new PreviewSessionEntity(
                sessionId,
                accessToken,
                ownerUserId,
                projectId,
                null,
                null,
                containerId,
                dockerService.getMappedPort(containerId),
                publicUrl(sessionId, accessToken),
                nextExpiry(),
                PreviewSessionStatus.PROVISIONING
        ));

        PreviewSessionEntity winner = resolveConcurrentProvisioning(projectId, ownerUserId, created);
        if (!winner.getId().equals(created.getId())) {
            // 승자도 준비 중인 세션이므로(아래 조회가 PROVISIONING 만 본다) 호출자는 폴링해야 한다.
            return new ProvisionOutcome(ProjectPreviewSessionResult.from(winner), true);
        }

        log.info("[ProjectPreview] 프로비저닝 시작: projectId={} sessionId={} repo={}",
                projectId, sessionId, sourceRepo);
        provisioner.provision(sessionId);
        return new ProvisionOutcome(ProjectPreviewSessionResult.from(created), true);
    }

    private Optional<ProvisionOutcome> attachToLiveSession(Long projectId, Long ownerUserId) {
        PreviewSessionEntity live = repository
                .findFirstByProjectIdAndOwnerUserIdAndStatusInOrderByLastAccessedAtDesc(
                        projectId, ownerUserId, LIVE_STATUSES)
                .orElse(null);
        if (live == null) {
            return Optional.empty();
        }
        if (isProvisioning(live)) {
            // 이미 같은 일이 진행 중이다. 두 번째 컨테이너를 띄우는 대신 진행 중인 것을 돌려준다.
            return Optional.of(new ProvisionOutcome(ProjectPreviewSessionResult.from(live), true));
        }
        if (dockerService.isContainerRunning(live.getContainerId())) {
            live.touch(nextExpiry());
            repository.save(live);
            return Optional.of(new ProvisionOutcome(ProjectPreviewSessionResult.from(live), false));
        }
        // ACTIVE 인데 컨테이너가 없다 — 행을 닫고 아래에서 새로 띄운다.
        discard(live, PreviewSessionStatus.EXPIRED);
        return Optional.empty();
    }

    /**
     * 같은 프로젝트에 동시에 들어온 프로비저닝 요청 중 하나만 남긴다.
     *
     * <p>버튼 더블클릭처럼 요청이 겹치면 두 요청 모두 "살아 있는 세션 없음"을 보고 각자 컨테이너를
     * 띄울 수 있다(그 사이에는 아직 어느 행도 저장되지 않았으므로). 잠금으로 막는 대신, 행을 저장한
     * 뒤 준비 중인 세션을 모두 읽어 가장 먼저 만들어진 하나를 승자로 정하고 진 쪽이 스스로 물러나게
     * 한다 — 판정 기준이 저장된 데이터(createdAt, 동률이면 sessionId)뿐이라 두 요청이 서로를 보든
     * 못 보든 같은 결론에 도달한다. 진 요청의 컨테이너는 여기서 즉시 제거되므로 1 GiB 짜리 컨테이너가
     * TTL 이 다할 때까지 놀고 있는 일은 없다.</p>
     *
     * <p>후보를 PROVISIONING 으로 한정하는 이유: 한 프로젝트에 ACTIVE 세션이 여럿 있을 수 있고
     * (작업마다 하나씩 생긴다), 그중 {@link #attachToLiveSession}이 고르지 않은 오래된 세션은
     * 컨테이너가 살아 있는지 확인한 적이 없다. 그런 세션에 양보하면 죽었을지도 모르는 주소를 200 으로
     * 돌려주게 된다 — 여기서 중재하려는 것은 "동시에 시작된 준비"뿐이다.</p>
     */
    private PreviewSessionEntity resolveConcurrentProvisioning(Long projectId,
                                                               Long ownerUserId,
                                                               PreviewSessionEntity created) {
        List<PreviewSessionEntity> live = repository.findByProjectIdAndOwnerUserIdAndStatusIn(
                projectId, ownerUserId, List.of(PreviewSessionStatus.PROVISIONING.name()));
        PreviewSessionEntity winner = live.stream()
                // createdAt 이 비어 있는 행(아직 타임스탬프가 채워지지 않은 경우)은 판정에서 가장
                // 뒤로 보낸다 — 여기서 NPE 가 나면 이미 컨테이너를 띄운 요청이 통째로 실패한다.
                .min(Comparator.comparing(PreviewSessionEntity::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PreviewSessionEntity::getId))
                .orElse(created);
        if (winner.getId().equals(created.getId())) {
            return created;
        }
        log.info("[ProjectPreview] 동시 요청 감지 — 후발 세션 취소: projectId={} cancelled={} kept={}",
                projectId, created.getId(), winner.getId());
        discard(created, PreviewSessionStatus.CLOSED);
        return winner;
    }

    private Project requireProject(Long projectId, Long ownerUserId) {
        return projectRepository.findByIdAndOwnerUserIdAndDeletedFalse(projectId, ownerUserId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다. projectId=" + projectId));
    }

    private void discard(PreviewSessionEntity session, PreviewSessionStatus status) {
        session.close(status);
        repository.save(session);
        dockerService.removeContainer(session.getContainerId());
    }

    private boolean isActive(PreviewSessionEntity session) {
        return PreviewSessionStatus.ACTIVE.name().equals(session.getStatus());
    }

    private boolean isProvisioning(PreviewSessionEntity session) {
        return PreviewSessionStatus.PROVISIONING.name().equals(session.getStatus());
    }

    private LocalDateTime nextExpiry() {
        return LocalDateTime.now().plus(properties.getTtl());
    }

    private String publicUrl(String sessionId, String accessToken) {
        String base = properties.getGatewayBaseUrl();
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return normalized + "/api/v1/previews/" + sessionId + "/" + accessToken + "/";
    }

    /** @param started 새로 준비를 시작했거나 준비 중이면 true, 이미 서빙 중인 세션에 붙었으면 false */
    public record ProvisionOutcome(ProjectPreviewSessionResult session, boolean started) {}
}
