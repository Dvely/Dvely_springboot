package com.example.dvely.webhook.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.common.worker.WorkerPollGate;
import com.example.dvely.webhook.application.WebhookService;
import com.example.dvely.webhook.domain.repository.WebhookDeliveryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

/**
 * #340 5-3 — 웹훅 배달 처리를 스케줄러 스레드에서 떼어낸 뒤의 계약.
 */
class WebhookDeliveryWorkerTest {

    private static final long BACKOFF_MS = 5_000L;

    private final WebhookDeliveryRepository repository = mock(WebhookDeliveryRepository.class);
    private final WebhookService webhookService = mock(WebhookService.class);

    /** 백오프가 이 파일의 dispatch 검증에 끼어들지 않도록 게이트를 항상 열어 둔다. */
    private static WorkerPollGate openGate() {
        AtomicLong nanos = new AtomicLong();
        return new WorkerPollGate(1000L, 30_000L, () -> nanos.addAndGet(TimeUnit.MINUTES.toNanos(1)));
    }

    private WebhookDeliveryWorker worker(Executor executor) {
        return new WebhookDeliveryWorker(repository, webhookService, openGate(), executor, BACKOFF_MS);
    }

    @Test
    void deliveriesAreHandedToTheExecutorInsteadOfRunningOnTheSchedulerThread() {
        List<Runnable> submitted = new ArrayList<>();
        WebhookDeliveryWorker worker = worker(submitted::add);
        when(repository.recoverAndClaimPending(anyString(), eq(10)))
                .thenReturn(List.of("delivery-1", "delivery-2"));

        worker.dispatchPendingDeliveries();

        // 스케줄러 스레드에서는 아무것도 처리하지 않는다 — 넘기기만 한다.
        assertThat(submitted).hasSize(2);
        verify(webhookService, never()).processDelivery(anyString());

        submitted.forEach(Runnable::run);
        verify(webhookService).processDelivery("delivery-1");
        verify(webhookService).processDelivery("delivery-2");
    }

    /**
     * 비동기로 넘기면 {@code TaskRejectedException} 이라는 새 실패 경로가 생긴다. 그것이 루프를
     * 끊으면 같은 배치의 뒤 배달이 PROCESSING 인 채 리스 만료(2분)까지 남는다 — 배포·클라우드
     * 워커에 있었던 것과 똑같은 버그를 여기서 새로 만들지 않는다.
     */
    @Test
    void anExecutorRejectionReleasesThatClaimAndStillDeliversTheRest() {
        List<Runnable> submitted = new ArrayList<>();
        Executor rejectsFirst = new Executor() {
            private boolean first = true;

            @Override
            public void execute(Runnable command) {
                if (first) {
                    first = false;
                    throw new TaskRejectedException("webhookExecutor 포화");
                }
                submitted.add(command);
            }
        };
        WebhookDeliveryWorker worker = worker(rejectsFirst);
        when(repository.recoverAndClaimPending(anyString(), eq(10)))
                .thenReturn(List.of("delivery-1", "delivery-2"));

        worker.dispatchPendingDeliveries();

        assertThat(submitted).hasSize(1);
        verify(repository).releaseClaim(eq("delivery-1"), anyString(), eq(BACKOFF_MS));
        verify(repository, never()).releaseClaim(eq("delivery-2"), anyString(), anyLong());
    }

    /** executor 스레드에서 튀어나온 예외가 조용히 사라지면 안 된다 — 워커가 잡아 남긴다. */
    @Test
    void anUnexpectedFailureInsideTheExecutorTaskDoesNotEscape() {
        List<Runnable> submitted = new ArrayList<>();
        WebhookDeliveryWorker worker = worker(submitted::add);
        when(repository.recoverAndClaimPending(anyString(), eq(10))).thenReturn(List.of("delivery-1"));
        doThrowOnProcess();

        worker.dispatchPendingDeliveries();

        assertThat(submitted).hasSize(1);
        submitted.get(0).run();   // 예외가 밖으로 나오면 이 줄에서 테스트가 깨진다
    }

    private void doThrowOnProcess() {
        org.mockito.Mockito.doThrow(new IllegalStateException("배달 행이 사라졌다"))
                .when(webhookService).processDelivery("delivery-1");
    }
}
