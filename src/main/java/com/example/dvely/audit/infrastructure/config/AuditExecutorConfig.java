package com.example.dvely.audit.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 감사 로그 INSERT 를 요청 스레드에서 떼어내는 전용 실행기.
 *
 * <p>동기 {@code REQUIRES_NEW} 시절에는 쓰기 요청 하나가 자기 트랜잭션 커넥션과 감사용 커넥션을
 * <b>동시에</b> 하나씩 쥐었다. 풀이 20개면 동시 쓰기 10건에서 이미 절반이 감사용으로 나간다.</p>
 */
@Configuration
public class AuditExecutorConfig {

    /**
     * 스레드를 하나만 두는 것이 이 설정의 핵심이다.
     *
     * <p>첫째, 감사 쓰기가 쓰는 커넥션이 앱 전체를 통틀어 최대 1개로 묶인다 — 이슈가 지목한 풀 고갈
     * 결합이 여기서 끊긴다. 둘째, INSERT 가 호출 순서대로 들어가 감사 기록의 시간 순서가 뒤집히지
     * 않는다. 감사 쓰기는 FK 없는 leaf 테이블에 INSERT 한 건이라 한 스레드로도 충분히 빠르다.</p>
     */
    @Bean
    public Executor auditLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("audit-");

        // 큐가 넘치면 호출 스레드에서 그대로 실행한다.
        //
        // 감사 로그를 조용히 버리는 것은 감사의 목적을 정면으로 해친다 - 없어진 줄도 모르는 기록은
        // 없는 기록이다. 그래서 버리는 선택지는 두지 않았다. 호출 스레드에서 실행하면 그 요청은
        // 느려지지만 그건 U10 이전의 동기 동작과 정확히 같다. 즉 과부하에서 최악이 "예전과 같아지는
        // 것"이지 "기록이 사라지는 것"이 아니다. 동시에 이건 자연스러운 배압이기도 하다 - 쓰기가
        // 밀리면 요청도 함께 느려져 큐가 무한정 자라지 않는다.
        //
        // ThreadPoolExecutor.CallerRunsPolicy 를 쓰지 않은 이유: 그건 실행기가 이미 종료된 경우
        // 아무 말 없이 버린다. 종료 중이라도 일단 이 스레드에서 시도하고, 실패하면 AuditRecorder 의
        // AUDIT_FALLBACK 로그로 흔적을 남기는 편이 낫다.
        executor.setRejectedExecutionHandler((task, pool) -> task.run());

        // 종료 시 큐에 남은 감사 기록을 흘려보내고 내려간다. 배포마다 마지막 몇 건이 사라지면
        // 하필 배포 직전 상황을 못 보게 된다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);

        executor.initialize();
        return executor;
    }

    /**
     * 큐 길이는 정확도가 중요한 값이 아니다. 넘치면 버리는 게 아니라 동기 실행으로 떨어지므로,
     * "잠깐의 몰림을 흡수할 만큼"이면 충분하고 그 이상은 종료 시 흘려보낼 양만 늘린다.
     */
    private static final int QUEUE_CAPACITY = 1_000;

    private static final int AWAIT_TERMINATION_SECONDS = 20;
}
