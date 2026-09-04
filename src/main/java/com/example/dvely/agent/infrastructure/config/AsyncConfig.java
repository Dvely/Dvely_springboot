package com.example.dvely.agent.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@EnableAsync
@EnableScheduling
@Configuration
public class AsyncConfig {

    // ADR-Y3 (#55): declared as the concrete ThreadPoolTaskExecutor type (not the Executor
    // interface, unlike the other beans below) so AgentRunWorker can @Qualifier-inject it and read
    // its queue/pool introspection methods (getThreadPoolExecutor().getQueue(), getPoolSize(),
    // getActiveCount()) for the capacity-aware claim estimate — Executor alone does not expose
    // those. Pool sizing itself (core2/max5/queue10) is unchanged: a rejection here is meant to be
    // a signal (log.warn / DISPATCH_REJECTED event), not something silently absorbed by a bigger
    // pool.
    @Bean("agentExecutor")
    public ThreadPoolTaskExecutor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("agent-");
        executor.initialize();
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
