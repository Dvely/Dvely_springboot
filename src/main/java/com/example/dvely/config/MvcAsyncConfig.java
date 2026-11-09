package com.example.dvely.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 비동기 처리(지금은 {@code StreamingResponseBody} 를 돌려주는 프리뷰 SSE 프록시뿐)를 도는 실행기(#624).
 *
 * <p><b>왜 직접 두는가.</b> 부트는 {@code applicationTaskExecutor} 빈이 있을 때만 그것을 MVC 에 건네는데, 그 빈은
 * {@code Executor} 빈이 하나도 없을 때만 만들어진다. {@code AsyncConfig} 에 실행기가 여럿 있어 만들어지지 않고, 그러면 MVC 는
 * 기본값 {@code MvcAsync} — 작업마다 플랫폼 스레드를 새로 만들고 상한이 없는 {@link SimpleAsyncTaskExecutor} — 로 돈다.
 * 프리뷰 SSE 스트림 하나가 스레드 하나를 스트림 수명만큼 쥐므로, 열린 탭 수만큼 플랫폼 스레드가 늘었다.</p>
 *
 * <p><b>가상 스레드 + 상한 + 넘치면 거절.</b> {@code agentEventExecutor} 와 같은 모양이다. 스트림은 기다리기만 하고 CPU 를 거의
 * 쓰지 않아 가상 스레드가 맞다. 상한에 닿았을 때의 기본 동작은 제출자(톰캣 요청 스레드)를 기다리게 하는 것이라, 그대로 두면
 * 요청 스레드가 스트림 수명만큼 묶인다. 그래서 거절한다. 프리뷰 SSE 는 이 상한보다 작은 자기 상한으로 먼저 거른다 —
 * 실행기가 거절하면 이미 연 업스트림 연결을 닫을 곳이 없기 때문이다({@code PreviewEventStreamLimiter}).</p>
 *
 * <p>타임아웃은 부트의 {@code spring.mvc.async.request-timeout} 으로 준다(부트가 같은 콜백에서 건다). 없으면 서블릿
 * 컨테이너 기본값(톰캣 30초)이 걸려 프리뷰 SSE 가 30초마다 끊겼다.</p>
 */
@Slf4j
@Configuration
public class MvcAsyncConfig implements WebMvcConfigurer {

    private final SimpleAsyncTaskExecutor mvcAsyncExecutor;

    public MvcAsyncConfig(@Qualifier("mvcAsyncExecutor") SimpleAsyncTaskExecutor mvcAsyncExecutor) {
        this.mvcAsyncExecutor = mvcAsyncExecutor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncExecutor);
    }

    /**
     * 종료 동작은 {@code agentEventExecutor} 와 같다. 스트림은 종료 유예 안에 끝나지 않으므로 기다리지 않고 인터럽트로 깨운다
     * — 프리뷰 프록시는 인터럽트를 받으면 업스트림을 닫고 끝난다. {@code taskTerminationTimeout} 이 0 이면 활성 스레드를
     * 추적하지 않아 인터럽트가 나가지 않으므로 반드시 양수여야 한다.
     */
    @Bean("mvcAsyncExecutor")
    public static SimpleAsyncTaskExecutor mvcAsyncExecutor(
            @Value("${qeploy.web.async.max-concurrent:200}") int maxConcurrent
    ) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("qeploy.web.async.max-concurrent 는 1 이상이어야 합니다: " + maxConcurrent);
        }
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("mvc-async-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(maxConcurrent);
        executor.setRejectTasksWhenLimitReached(true);
        executor.setTaskTerminationTimeout(2000);
        executor.setCancelRemainingTasksOnClose(true);
        log.info("[MvcAsyncConfig] MVC 비동기 실행기 가상 스레드, 동시 상한={}", maxConcurrent);
        return executor;
    }
}
