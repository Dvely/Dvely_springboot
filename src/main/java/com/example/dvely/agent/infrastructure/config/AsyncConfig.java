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
