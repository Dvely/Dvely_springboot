package com.example.dvely.agent.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Slf4j
@EnableAsync
@EnableScheduling
@Configuration
public class AsyncConfig {

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
        executor.initialize();
        return executor;
    }

    @Bean("agentEventExecutor")
    public Executor agentEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-event-");
        executor.initialize();
        return executor;
    }

    @Bean("deploymentExecutor")
    public Executor deploymentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("deployment-");
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
        executor.initialize();
        return executor;
    }
}
