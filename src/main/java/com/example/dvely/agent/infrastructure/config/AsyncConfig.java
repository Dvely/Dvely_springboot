package com.example.dvely.agent.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Slf4j
@EnableAsync
@Configuration
public class AsyncConfig {

    // @EnableScheduling 은 DvelyApplication 에 있다. 여기에도 달려 있었는데(중복), 스케줄러는
    // 27개 파일에 흩어진 전역 관심사라 agent 도메인 밑의 설정 클래스가 아니라 앱 클래스가 소유한다.
    //
    // 스케줄러 스레드 수는 spring.task.scheduling.pool.size 로 준다 — 여기에 TaskScheduler 빈을
    // 두면 부트의 자동 구성(@ConditionalOnMissingBean)이 물러나 그 설정이 통째로 무시된다.

    // 아래 executor 들의 종료 유예. 기본값은 유예 0 이라, 컨텍스트가 닫히는 순간 shutdownNow()
    // 가 스레드를 인터럽트해 CODE·배포·프리뷰가 하던 일이 중간에서 잘린다(리스 복구가 재시도하지만
    // 그때까지 쓴 LLM 비용은 버린다).
    //
    // 값의 상한은 pm2 kill_timeout 30s 다(deploy/ecosystem.config*.js.example). 웹 단계
    // (spring.lifecycle.timeout-per-shutdown-phase, 10s)를 빼고 남는 시간을 executor 성격에
    // 맞춰 나눈다 — 여섯 개가 동시에 막히면 그 합이 30s 를 넘어 SIGKILL 이지만, 그건 지금과
    // 같은 결과일 뿐 더 나빠지지 않는다.
    private static void gracefulShutdown(ThreadPoolTaskExecutor executor, int awaitSeconds) {
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitSeconds);
    }

    // ADR-Y3 (#55): declared as the concrete ThreadPoolTaskExecutor type (not the Executor
    // interface, unlike the other beans below) so AgentRunWorker can @Qualifier-inject it and read
    // its queue/pool introspection methods (getThreadPoolExecutor().getQueue(), getPoolSize(),
    // getActiveCount()) for the capacity-aware claim estimate — Executor alone does not expose
    // those. 거부는 여전히 신호다(log.warn / DISPATCH_REJECTED) — 큐를 무한정 키워 삼키지 않는다.
    //
    // <b>실질 동시 실행은 corePoolSize 다.</b> ThreadPoolExecutor 는 큐가 가득 차야 코어를 넘겨
    // 스레드를 늘리므로, 예전 설정(core2/max5/queue10)에서 max5 는 도달할 수 없는 값이었다 —
    // 큐 10 이 다 차기 전에는 세 번째 스레드가 생기지 않아 언제나 2 개만 돌았다. dev 전체 로그에
    // 등장한 스레드 이름이 agent-1, agent-2 둘뿐인 것이 그 증거다.
    //
    // 그래서 다른 사용자의 CODE 태스크 하나(LLM 40 라운드, 14 분)가 두 자리 중 하나를 오래 쥐면
    // 나머지 사용자는 큐에서 기다린다. 실제로 2026-09-08 에 한 사용자의 CODE + BACKEND_DEPLOY 가
    // 두 자리를 다 써서, 다른 프로젝트의 승인된 태스크가 QUEUED 로 1 분 30 초 넘게 멈췄다.
    //
    // 크기 근거: 이 태스크들은 벽시계 시간의 대부분을 LLM 응답 대기로 쓴다(라운드당 6~20 초 ×
    // 수십 회). CPU 를 실제로 쓰는 구간은 컨테이너 안 빌드뿐이라, 코어 수(dev 2 vCPU)보다 조금
    // 넉넉히 잡는 편이 처리량에 이롭다. 큐도 8 로 줄여 max 가 도달 가능한 값이 되게 했다.
    // 값은 환경마다 자원이 다르므로 설정으로 뺐다 — 배포 없이 조절할 수 있어야 한다.
    @Bean("agentExecutor")
    public ThreadPoolTaskExecutor agentExecutor(
            @Value("${qeploy.agent.executor.core-size:4}") int coreSize,
            @Value("${qeploy.agent.executor.max-size:8}") int maxSize,
            @Value("${qeploy.agent.executor.queue-capacity:8}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("agent-");
        // 코어를 늘린 만큼 놀 때는 회수한다 — 한가한 시간에 스레드를 붙들고 있을 이유가 없다.
        executor.setAllowCoreThreadTimeOut(true);
        // CODE 태스크 한 건은 LLM 40 라운드로 십수 분까지 간다 — 종료 유예로 완주시킬 수 있는
        // 길이가 아니다. 10s 는 완주를 노린 값이 아니라, 마침 짧은 구간(태스크 상태 전이·컨테이너
        // 정리·이벤트 기록)에 있던 스레드가 그 구간만은 마치게 하는 값이다.
        gracefulShutdown(executor, 10);
        executor.initialize();
        log.info("[AsyncConfig] agentExecutor 동시 실행={} (max={}, queue={})",
                coreSize, maxSize, queueCapacity);
        return executor;
    }

    // 메시지 한 건의 Decision(LLM 호출→AgentPlan) 을 요청 스레드에서 떼어내 여기서 돈다
    // (ChatCommandService#sendMessage 비동기화). 한 건은 LLM 응답을 기다리는 I/O 대기라 CPU 를
    // 거의 안 쓰므로 코어 수보다 넉넉히 잡는다. read timeout(#238, 180s)이 개별 호출을 유계로
    // 묶으므로 스레드가 무한 점유되지 않는다. 큐를 크게 둬 실질적으로 거부가 나지 않게 하되,
    // 거부가 나더라도 호출부(sendMessage)가 그 태스크를 FAILED 로 닫으므로 PENDING 고착은 없다.
    @Bean("agentDecisionExecutor")
    public Executor agentDecisionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-decision-");
        // LLM 왕복 한 번(read timeout 180s)을 기다릴 수는 없다. 이미 응답을 받아 AgentPlan 을
        // 저장하는 뒷부분만 넘기면 되므로 짧게 둔다. 못 끝낸 건은 호출부가 FAILED 로 닫는다.
        gracefulShutdown(executor, 3);
        executor.initialize();
        return executor;
    }

    // 여기만 ThreadPoolTaskExecutor 가 아니다 — 그 타입으로는 이 풀이 원하는 것을 만들 수 없다.
    //
    // 이 풀이 받는 것은 짧은 작업이 아니라 SSE 스트리밍 루프다. 한 건이 최대 5분
    // (AgentEventStreamService.STREAM_TIMEOUT_MS) 동안 이벤트를 기다리며 살아 있고, 그 동안 쓰는
    // CPU 는 없다. 그런데 ThreadPoolExecutor 는 <b>큐가 가득 차야</b> 코어를 넘겨 스레드를 늘리므로,
    // 예전 설정(core2/max10/queue100)에서 실질 동시 스트림은 언제나 2 개였다 — 세 번째부터는 큐에
    // 들어가 앞의 스트림이 5분을 채우고 끝날 때까지 아예 시작되지 않았고, FE 는 스트림이 안 열려
    // 조용히 5초 폴링으로 강등됐다. 큐를 0 으로 줄여 max 를 도달 가능하게 만드는 길도 있지만,
    // 그러면 이번엔 max 가 곧 동시 스트림 수의 하드 상한이 된다.
    //
    // 스레드를 아낄 이유가 없는 작업이므로 가상 스레드로 "한 스트림 = 한 스레드"를 그냥 허용한다
    // (Java 25 라 synchronized 로 인한 pinning 도 없다).
    //
    // <b>종료 동작은 예전과 같게 유지한다.</b> 이 루프는 InterruptedException 을 정상 종료 경로로
    // 처리하도록 짜여 있다(emitter.complete()). 완료를 기다리게 두면 열려 있는 스트림 수명만큼
    // 종료가 멈추므로, 인터럽트로 깨우고 그 정리가 끝날 2s 만 기다린다 — cancelRemainingTasksOnClose
    // 가 활성 스레드를 인터럽트하고 taskTerminationTimeout 이 그 대기 상한이다(= 예전
    // shutdownNow + awaitTermination(2s)). taskTerminationTimeout 이 0 이면 활성 스레드를 추적조차
    // 하지 않아 인터럽트가 나가지 않는다 — 반드시 양수여야 한다. close() 는 SimpleAsyncTaskExecutor
    // 가 AutoCloseable 이라 컨테이너가 소멸 단계에 알아서 부른다.
    //
    // 상한을 두는 이유: 가상 스레드는 싸도 그 뒤의 DB 커넥션 풀(20)은 싸지 않다. 상한에 닿으면
    // 제출을 막지 않고 거부한다 — 기본 동작은 제출자를 대기시키는 것이라, 그대로 두면 톰캣 요청
    // 스레드가 스트림 수명만큼 묶인다. 거부는 AgentEventStreamService 가 받아 스트림을 열지 않고,
    // FE 는 예전과 같은 폴링 강등으로 떨어진다.
    @Bean("agentEventExecutor")
    public SimpleAsyncTaskExecutor agentEventExecutor(
            @Value("${qeploy.agent.event-stream.max-concurrent:200}") int maxConcurrent
    ) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("agent-event-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(maxConcurrent);
        executor.setRejectTasksWhenLimitReached(true);
        executor.setTaskTerminationTimeout(2000);
        executor.setCancelRemainingTasksOnClose(true);
        log.info("[AsyncConfig] agentEventExecutor 가상 스레드, 동시 스트림 상한={}", maxConcurrent);
        return executor;
    }

    @Bean("deploymentExecutor")
    public Executor deploymentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("deployment-");
        // GitHub Actions 디스패치와 그 결과 기록 한 왕복. 그 사이에서 잘리면 배포는 떴는데
        // 우리 기록만 없는 상태가 되고, 회수가 recovery 폴러 몫으로 넘어간다.
        gracefulShutdown(executor, 5);
        executor.initialize();
        return executor;
    }

    // 프로젝트 단위 프리뷰 프로비저닝(clone → npm install → build → serve). 한 건이 수 분 동안
    // 스레드를 붙들고, 그 동안 1 GiB/1 vCPU 컨테이너가 하나씩 물려 있으므로 동시 실행 수를 낮게
    // 잡는다 — 큐가 차서 대기하는 것이, 호스트가 컨테이너에 눌려 이미 떠 있는 프리뷰까지 느려지는
    // 것보다 낫다.
    @Bean("previewExecutor")
    public Executor previewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("preview-");
        // clone→install→build→serve 는 수 분이라 완주는 못 한다. 다만 컨테이너를 만든 직후
        // 끊기면 아무도 소유하지 않은 컨테이너가 남으므로, 생성과 기록 사이만은 넘기게 한다.
        gracefulShutdown(executor, 5);
        executor.initialize();
        return executor;
    }

    // #340 5-3: 웹훅 배달 처리를 스케줄러 스레드에서 떼어낸다. 핸들러가 GitHub API 를 호출하므로
    // 한 배달이 느리면 그 동안 이 워커의 다음 폴링이 통째로 밀리고, 스케줄러 풀을 공유하는 다른
    // 잡까지 굶는다. 한 폴링이 최대 CLAIM_BATCH_SIZE(10) 건을 넘기므로 큐를 그보다 넉넉히 둔다.
    @Bean("webhookExecutor")
    public ThreadPoolTaskExecutor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("webhook-");
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();
        return executor;
    }

    // #340 5-5: 도메인 검증 프로브(Cloudflare + GitHub + HTTPS)를 스케줄러 스레드에서 떼어낸다.
    // 배치 20건을 직렬로 도는 동안 한 건이 타임아웃까지 버티면 그 시간이 그대로 스케줄러 점유가
    // 된다. 동시 실행을 낮게 두는 이유는 이 호출들이 외부 API 레이트 리밋을 쓰기 때문이다.
    @Bean("domainVerificationExecutor")
    public ThreadPoolTaskExecutor domainVerificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("domain-verify-");
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();
        return executor;
    }

    // #340 5-10: 도커 prune 처럼 오래 걸리는 정비 작업 전용. prune 3회가 도커 데몬을 잠시 붙잡는
    // 동안 스케줄러 스레드를 점유하지 않게 한다. 6시간에 한 번 도는 일이라 놀 때는 스레드를
    // 회수한다(allowCoreThreadTimeOut).
    @Bean("maintenanceExecutor")
    public ThreadPoolTaskExecutor maintenanceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("maintenance-");
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();
        return executor;
    }

    @Bean("cloudConnectionExecutor")
    public Executor cloudConnectionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("cloud-connection-");
        // AWS 호출 한 번과 그 결과 기록 정도면 충분하다.
        gracefulShutdown(executor, 3);
        executor.initialize();
        return executor;
    }
}
