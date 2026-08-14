package com.example.dvely.preview.infrastructure.persistence.entity;

import com.example.dvely.preview.application.result.PreviewSessionInfo;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "preview_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PreviewSessionEntity {

    // failure_reason 컬럼 길이(V31)와 같은 값. 초과분은 markFailed 에서 잘라낸다.
    private static final int MAX_FAILURE_REASON_LENGTH = 500;

    @Id
    @Column(name = "preview_session_id", length = 36)
    private String id;

    @Column(name = "access_token", nullable = false, unique = true, length = 64)
    private String accessToken;

    @Column(name = "user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "chat_session_id")
    private Long conversationId;

    // NULL 은 "작업이 만든 세션이 아님" — 프로젝트 진입/버튼으로 띄운 프로젝트 단위 프리뷰다
    // (V31). 값이 있으면 여전히 실재하는 agent_runs 행이어야 한다(FK 유지).
    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "container_id", nullable = false, length = 128)
    private String containerId;

    @Column(name = "host_port", nullable = false)
    private int hostPort;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "public_url", nullable = false, length = 1000)
    private String publicUrl;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_accessed_at", nullable = false)
    private LocalDateTime lastAccessedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public PreviewSessionEntity(String id,
                                String accessToken,
                                Long ownerUserId,
                                Long projectId,
                                Long conversationId,
                                String taskId,
                                String containerId,
                                int hostPort,
                                String publicUrl,
                                LocalDateTime expiresAt) {
        this(id, accessToken, ownerUserId, projectId, conversationId, taskId, containerId,
                hostPort, publicUrl, expiresAt, PreviewSessionStatus.ACTIVE);
    }

    /**
     * 시작 상태를 지정해 만드는 생성자. 프로젝트 단위 프리뷰는 컨테이너만 뜬 채 워크스페이스 준비가
     * 남아 있는 시점에 행을 남기므로 {@link PreviewSessionStatus#PROVISIONING}으로 시작한다 —
     * 그 사이 게이트웨이가 이 세션을 열어주면 아직 아무것도 서빙하지 않는 포트로 프록시하게 된다.
     */
    public PreviewSessionEntity(String id,
                                String accessToken,
                                Long ownerUserId,
                                Long projectId,
                                Long conversationId,
                                String taskId,
                                String containerId,
                                int hostPort,
                                String publicUrl,
                                LocalDateTime expiresAt,
                                PreviewSessionStatus status) {
        this.id = id;
        this.accessToken = accessToken;
        this.ownerUserId = ownerUserId;
        this.projectId = projectId;
        this.conversationId = conversationId;
        this.taskId = taskId;
        this.containerId = containerId;
        this.hostPort = hostPort;
        this.status = status.name();
        this.publicUrl = publicUrl;
        this.expiresAt = expiresAt;
        this.lastAccessedAt = LocalDateTime.now();
    }

    public void touch(LocalDateTime expiresAt) {
        lastAccessedAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    /**
     * Rebinds the tracked host port after a container restart (issue #71 — CloudOps RESTART).
     * Docker reassigns a fresh ephemeral host port on every container start when the original
     * binding was requested as `Ports.Binding.bindPort(0)` (see
     * {@code DockerContainerService#createAndStartContainer}), and {@code restartContainer} is a
     * stop+start under the hood — so the port captured at session-creation time goes stale the
     * moment a restart completes. {@code publicUrl} itself is untouched by this: it only encodes
     * {@code id}/{@code accessToken} (see the constructor below), never the port. It's this
     * {@code hostPort} column that {@code PreviewGatewayService} reads on every proxied request,
     * so leaving it stale here is exactly what turns a "restart succeeded" response into a 502 on
     * the next gateway hit.
     */
    public void rebindPort(int hostPort) {
        this.hostPort = hostPort;
    }

    /**
     * accessToken을 새로 발급하고 공개 주소를 그에 맞춰 갱신한다 (Issue #77 G4).
     *
     * <p>기존 토큰은 이 시점에 죽는다 — 채팅 기록·브라우저 히스토리·화면 공유로 흘러나간 예전
     * 주소가 계속 열리는 것이 G4가 지적한 문제였고, 소유자가 프리뷰를 다시 열 때마다 그 창을
     * 닫는 것이 이 메서드의 목적이다.</p>
     */
    public void rotateAccess(String accessToken, String publicUrl) {
        this.accessToken = accessToken;
        this.publicUrl = publicUrl;
    }

    public void close(PreviewSessionStatus status) {
        this.status = status.name();
    }

    /**
     * 프로비저닝이 끝나 서빙 가능해진 시점의 전이. 만료 시각을 이때부터 다시 세는 것이 핵심이다 —
     * 생성 시점 기준으로 두면 install/build 에 쓴 몇 분이 사용자가 프리뷰를 볼 수 있는 시간에서
     * 그대로 깎여 나간다.
     */
    public void activate(LocalDateTime expiresAt) {
        this.status = PreviewSessionStatus.ACTIVE.name();
        this.failureReason = null;
        touch(expiresAt);
    }

    /**
     * 프로비저닝 실패. 사유는 컬럼 길이(500)에 맞춰 잘라 담는다 — 여기 들어오는 값은 빌드 로그
     * 꼬리처럼 길이가 정해져 있지 않은 텍스트라, 자르지 않으면 저장 자체가 실패해 실패 사유가
     * 통째로 사라진다.
     */
    public void markFailed(String reason) {
        this.status = PreviewSessionStatus.FAILED.name();
        this.failureReason = reason == null || reason.length() <= MAX_FAILURE_REASON_LENGTH
                ? reason
                : reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    public PreviewSessionInfo toInfo() {
        return new PreviewSessionInfo(
                id,
                ownerUserId,
                projectId,
                conversationId,
                taskId,
                containerId,
                hostPort,
                publicUrl,
                expiresAt
        );
    }
}
