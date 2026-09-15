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

    /**
     * 게이트웨이가 프록시할 컨테이너 주소 (#358).
     *
     * <p>배포 이전에 만들어진 행은 비어 있다 — 그때는 호스트 포트로 프록시했다. 게이트웨이가 첫
     * 요청에서 컨테이너를 조회해 채운다(지연 해석). 세션은 수명이 짧아 곧 사라지지만, 배포 순간에
     * 열려 있던 프리뷰를 끊지 않기 위한 것이다.</p>
     */
    @Column(name = "container_ip", length = 45)
    private String containerIp;

    /**
     * 더는 쓰지 않는다. #358 에서 호스트 포트 발행을 없앴다 — 새 세션은 넣을 값이 없어 NULL 이다.
     * 컬럼을 드롭하지 않은 것은 롤링 배포 중 이 값을 읽던 인스턴스가 함께 돌 수 있어서다.
     */
    @Column(name = "host_port")
    private Integer hostPort;

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
                                String containerIp,
                                String publicUrl,
                                LocalDateTime expiresAt) {
        this(id, accessToken, ownerUserId, projectId, conversationId, taskId, containerId,
                containerIp, publicUrl, expiresAt, PreviewSessionStatus.ACTIVE);
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
                                String containerIp,
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
        this.containerIp = containerIp;
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
     * 컨테이너를 다시 만들거나 재시작한 뒤 프록시 타깃을 갱신한다 (issue #71 — CloudOps RESTART).
     *
     * <p>Docker 는 컨테이너를 다시 시작할 때마다 새 IP 를 줄 수 있고 {@code restartContainer} 는
     * 내부적으로 stop+start 다. 세션 생성 시점에 잡아둔 주소는 재시작이 끝나는 순간 낡는다.
     * {@code publicUrl} 은 {@code id}/{@code accessToken} 만 담으므로 영향이 없다 — 게이트웨이가
     * 매 요청에 읽는 것은 이 {@code containerIp} 이고, 낡은 채 두는 것이 "재시작 성공" 응답을
     * 다음 요청의 502 로 바꾸는 지점이다.</p>
     *
     * <p>#358 이전에는 같은 문제가 발행 포트 재할당으로 나타났다. 원인은 바뀌었지만 성질은 같다.</p>
     */
    public void rebindContainerIp(String containerIp) {
        this.containerIp = containerIp;
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
                containerIp,
                publicUrl,
                expiresAt
        );
    }
}
