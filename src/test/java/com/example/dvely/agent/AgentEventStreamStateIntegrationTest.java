package com.example.dvely.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.application.dto.AgentTask;
import com.example.dvely.agent.application.dto.AgentTaskEvent;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.stream.AgentEventBus;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #339 4-1·4-2 를 <b>실제 MySQL</b> 로 본다. 모킹으로는 증명되지 않는 두 가지가 있다.
 *
 * <p>하나는 {@code findStreamState} 의 스칼라 서브쿼리가 실제로 도는가 — 상태와 max(event_id)를
 * 한 문장으로 가져오는 것이 4-1 의 전부이므로, 이 쿼리가 깨지면 단위 전체가 무의미하다.</p>
 *
 * <p>다른 하나는 깨우기가 <b>커밋 뒤에</b> 오는가. 커밋 전에 깨우면 스트림이 아직 보이지 않는
 * 행을 찾다가 헛조회하고 도로 눕는다 — 그러면 이벤트가 폴백 간격(5초)만큼 늦어져, 깨우기를
 * 넣은 의미가 사라진다.</p>
 */
@SpringBootTest
class AgentEventStreamStateIntegrationTest {

    @Autowired
    private TaskStore taskStore;
    @Autowired
    private AgentEventBus eventBus;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 상태와_마지막_이벤트_번호를_한_문장으로_돌려준다() {
        Long userId = seedUser();
        String taskId = seedTask(userId);

        TaskStore.StreamState afterCreate = taskStore.getStreamState(taskId, userId);
        assertThat(afterCreate).isNotNull();
        assertThat(afterCreate.status()).isEqualTo(TaskStatus.QUEUED);
        // save() 가 CREATED 이벤트를 남긴다.
        assertThat(afterCreate.lastEventId()).isPositive();

        taskStore.appendStepEvent(taskId, "STEP_STARTED", TaskStatus.RUNNING, "코드 생성", 1, 2, "CODE");

        TaskStore.StreamState afterStep = taskStore.getStreamState(taskId, userId);
        assertThat(afterStep.lastEventId()).isGreaterThan(afterCreate.lastEventId());
        // 그 번호가 실제 마지막 이벤트와 같은지 — 루프는 이 값만 보고 새 이벤트 유무를 판단한다.
        List<AgentTaskEvent> events = taskStore.getEventsSince(taskId, 0L);
        assertThat(events.get(events.size() - 1).eventId()).isEqualTo(afterStep.lastEventId());
    }

    @Test
    void 남의_태스크는_빈_결과다() {
        Long owner = seedUser();
        Long stranger = seedUser();
        String taskId = seedTask(owner);

        assertThat(taskStore.getStreamState(taskId, owner)).isNotNull();
        assertThat(taskStore.getStreamState(taskId, stranger)).isNull();
    }

    @Test
    void 커서_뒤의_이벤트만_돌려준다() {
        Long userId = seedUser();
        String taskId = seedTask(userId);
        long created = taskStore.getStreamState(taskId, userId).lastEventId();

        taskStore.appendStepEvent(taskId, "STEP_STARTED", TaskStatus.RUNNING, "두번째", 1, 2, "CODE");

        assertThat(taskStore.getEventsSince(taskId, created))
                .singleElement()
                .satisfies(event -> assertThat(event.message()).isEqualTo("두번째"));
    }

    @Test
    void 깨우기는_커밋_뒤에_오고_그때는_이벤트가_이미_보인다() throws Exception {
        Long userId = seedUser();
        String taskId = seedTask(userId);
        long cursor = taskStore.getStreamState(taskId, userId).lastEventId();

        try (AgentEventBus.Subscription subscription = eventBus.subscribe(taskId)) {
            transactionTemplate.executeWithoutResult(status -> {
                taskStore.appendStepEvent(taskId, "STEP_STARTED", TaskStatus.RUNNING, "진행", 1, 2, "CODE");
                try {
                    // 아직 커밋 전이다 — 여기서 깨우면 스트림이 안 보이는 행을 찾다 헛조회한다.
                    assertThat(subscription.await(0)).isFalse();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });

            assertThat(subscription.await(2_000)).isTrue();
            assertThat(taskStore.getEventsSince(taskId, cursor))
                    .isNotEmpty()
                    .anySatisfy(event -> assertThat(event.message()).isEqualTo("진행"));
        }
    }

    private Long seedUser() {
        return userRepository.save(
                new User(new GithubId("i339-stream-" + System.nanoTime()), "octo", null)).getId();
    }

    private String seedTask(Long userId) {
        String taskId = "i339-" + System.nanoTime();
        taskStore.save(new AgentTask(
                taskId, userId, null, null, TaskStatus.QUEUED,
                null, null, null, null, Instant.now()));
        return taskId;
    }
}
