package com.example.dvely.preview.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.auth.infrastructure.config.JwtProperties;
import com.example.dvely.common.limit.UserLimitProperties;
import com.example.dvely.config.CorsProperties;
import com.example.dvely.preview.application.result.PreviewSessionListResult;
import com.example.dvely.preview.domain.value.PreviewSessionStatus;
import com.example.dvely.preview.infrastructure.config.PreviewGatewayUrlResolver;
import com.example.dvely.preview.infrastructure.config.PreviewProperties;
import com.example.dvely.preview.infrastructure.persistence.entity.PreviewSessionEntity;
import com.example.dvely.preview.infrastructure.persistence.repository.SpringDataPreviewSessionRepository;
import com.example.dvely.preview.infrastructure.security.PreviewAccessCookies;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 내 프리뷰 목록(#651)의 항목을 맞게 만드는지, 그리고 목록이 말하는 {@code closable} 이 실제 닫기 경로(#650)의 결과와
 * 언제나 같은지 본다 — FE 는 이 값으로 버튼을 잠근다.
 */
class PreviewSessionListServiceTest {

    private static final Long OWNER = 7L;

    private final SpringDataPreviewSessionRepository repository = mock(SpringDataPreviewSessionRepository.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final PreviewSessionListService service =
            new PreviewSessionListService(repository, taskStore, projects, new UserLimitProperties(2, 3));

    @Test
    void eachItemSaysWhetherItCanBeClosedAndWhy() {
        live(session("project", null, PreviewSessionStatus.ACTIVE, 11L),
                session("waiting", "t-wait", PreviewSessionStatus.ACTIVE, 11L),
                session("failed", "t-failed", PreviewSessionStatus.ACTIVE, 11L),
                session("no-run", "t-gone", PreviewSessionStatus.ACTIVE, 12L),
                session("preparing", null, PreviewSessionStatus.PROVISIONING, 11L));
        when(taskStore.statusesOf(anyCollection())).thenReturn(Map.of(
                "t-wait", TaskStatus.WAITING_RESULT_APPROVAL,
                "t-failed", TaskStatus.FAILED));
        // 12 번 프로젝트는 행이 없다(이름 없이 항목만 남는다). 목은 when(...) 밖에서 만든다 — 안에서 스터빙하면 중첩으로 실패한다
        Project shop = project(11L, "쇼핑몰");
        when(projects.findAllByIdInAndOwnerUserId(anyCollection(), eq(OWNER))).thenReturn(List.of(shop));

        PreviewSessionListResult result = service.list(OWNER);

        assertThat(result.maxLivePreviews()).isEqualTo(3);
        assertThat(result.items()).extracting(PreviewSessionListResult.Item::sessionId)
                .containsExactly("project", "waiting", "failed", "no-run", "preparing");
        assertThat(item(result, "project")).satisfies(item -> {
            assertThat(item.closable()).isTrue();
            assertThat(item.closeBlockedReason()).isNull();
            assertThat(item.taskStatus()).isNull();
            assertThat(item.projectName()).isEqualTo("쇼핑몰");
        });
        assertThat(item(result, "waiting")).satisfies(item -> {
            assertThat(item.closable()).isFalse();
            assertThat(item.closeBlockedReason()).isEqualTo("WAITING_RESULT_APPROVAL");
            assertThat(item.taskStatus()).isEqualTo(TaskStatus.WAITING_RESULT_APPROVAL);
        });
        // 실패한 작업의 프리뷰는 닫을 수 있다. FE 는 taskStatus 로 "재시도가 처음부터" 를 경고한다
        assertThat(item(result, "failed")).satisfies(item -> {
            assertThat(item.closable()).isTrue();
            assertThat(item.taskStatus()).isEqualTo(TaskStatus.FAILED);
        });
        assertThat(item(result, "no-run")).satisfies(item -> {
            assertThat(item.closable()).isTrue();
            assertThat(item.taskStatus()).isNull();
            assertThat(item.projectName()).isNull();
        });
        assertThat(item(result, "preparing")).satisfies(item -> {
            assertThat(item.closable()).isFalse();
            assertThat(item.closeBlockedReason()).isEqualTo("PROVISIONING");
        });
    }

    @Test
    void anEmptyListReadsNothingElse() {
        live();
        when(taskStore.statusesOf(anyCollection())).thenReturn(Map.of());

        PreviewSessionListResult result = service.list(OWNER);

        assertThat(result.items()).isEmpty();
        verify(projects, never()).findAllByIdInAndOwnerUserId(anyCollection(), anyLong());
    }

    /**
     * 목록의 {@code closable} 과 {@code DELETE /preview-sessions/{id}} 의 실제 결과를 모든 경우에 맞춰 본다 — 세션 상태 2 ×
     * (프로젝트 프리뷰, 런 행 없음, 작업 상태 10). 둘이 어긋나면 FE 는 누르면 409·404 가 나는 버튼을 열어 두거나, 닫을 수
     * 있는 것을 잠근다.
     */
    @Test
    void closableAgreesWithWhatClosingActuallyDoes() {
        List<String> mismatches = new ArrayList<>();
        for (PreviewSessionStatus sessionStatus : List.of(PreviewSessionStatus.ACTIVE, PreviewSessionStatus.PROVISIONING)) {
            for (Case c : cases()) {
                PreviewSessionEntity forList = session("s", c.taskId(), sessionStatus, 11L);
                live(forList);
                when(taskStore.statusesOf(anyCollection())).thenReturn(
                        c.taskStatus() == null ? Map.of() : Map.of(c.taskId(), c.taskStatus()));
                when(projects.findAllByIdInAndOwnerUserId(anyCollection(), eq(OWNER))).thenReturn(List.of());
                boolean listed = service.list(OWNER).items().getFirst().closable();

                boolean closed = closes(session("s", c.taskId(), sessionStatus, 11L), c);
                if (listed != closed) {
                    mismatches.add(sessionStatus + "/" + c + ": 목록 closable=" + listed + ", 실제로 닫힘=" + closed);
                }
            }
        }
        assertThat(mismatches).isEmpty();
    }

    /** 실제 닫기 경로({@link PreviewSessionService#closeOwned})가 이 세션을 닫는가. 409·404 는 닫지 않은 것이다. */
    private static boolean closes(PreviewSessionEntity session, Case c) {
        SpringDataPreviewSessionRepository repo = mock(SpringDataPreviewSessionRepository.class);
        TaskStore tasks = mock(TaskStore.class);
        PreviewSessionService closer = new PreviewSessionService(repo, mock(DockerContainerService.class), tasks,
                properties(), new PreviewGatewayUrlResolver(properties(), new CorsProperties(List.of(), List.of())),
                new PreviewAccessCookies(new JwtProperties("test-secret-key-that-is-long-enough-32", 3600000L, 7200000L)),
                mock(PreviewRuntimeConfigService.class), mock(PreviewCapacity.class));
        when(repo.findByIdAndOwnerUserId(session.getId(), OWNER)).thenReturn(Optional.of(session));
        if (c.taskStatus() != null) {
            when(tasks.get(c.taskId())).thenReturn(
                    new AgentTask(c.taskId(), OWNER, 11L, 21L, c.taskStatus(), null, null, null, null, Instant.now()));
        }
        try {
            return closer.closeOwned(session.getId(), OWNER);
        } catch (PreviewInUseException e) {
            return false;
        }
    }

    private record Case(String taskId, TaskStatus taskStatus) {
    }

    private static List<Case> cases() {
        return Stream.concat(
                Stream.of(new Case(null, null), new Case("gone", null)),
                Arrays.stream(TaskStatus.values()).map(status -> new Case("t", status))
        ).toList();
    }

    private void live(PreviewSessionEntity... sessions) {
        when(repository.findByOwnerUserIdAndStatusInOrderByLastAccessedAtAsc(eq(OWNER), anyCollection()))
                .thenReturn(List.of(sessions));
    }

    private static PreviewSessionListResult.Item item(PreviewSessionListResult result, String sessionId) {
        return result.items().stream().filter(item -> item.sessionId().equals(sessionId)).findFirst().orElseThrow();
    }

    private static Project project(Long id, String name) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        when(project.getName()).thenReturn(name);
        return project;
    }

    private static PreviewSessionEntity session(String id, String taskId, PreviewSessionStatus status, Long projectId) {
        return new PreviewSessionEntity(id, "token-" + id, OWNER, projectId, null, taskId, "container-" + id,
                "172.18.0.2", "http://preview/" + id, LocalDateTime.now().plusMinutes(30), status);
    }

    private static PreviewProperties properties() {
        PreviewProperties properties = new PreviewProperties();
        properties.setGatewayBaseUrl("https://preview.qeploy.test");
        properties.setTtl(Duration.ofMinutes(30));
        return properties;
    }
}
