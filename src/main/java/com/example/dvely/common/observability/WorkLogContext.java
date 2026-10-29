package com.example.dvely.common.observability;

import org.slf4j.MDC;

/**
 * 비동기 작업 스레드의 로그 줄에 작업 식별자를 얹는다(#511 B12).
 *
 * <p>에이전트 실행·배포 실행은 요청 스레드가 아니라 실행기 스레드에서 돌아 {@code requestId} 가 비어 있다. 그 아래에서
 * 로그를 남기는 LLM·git·docker 호출은 대개 어느 태스크·배포의 일인지 적지 않으므로, 동시에 둘 이상 돌면 줄이 섞인다.
 * 실행 진입점에서 이 범위를 열면 그 스레드의 모든 줄에 붙는다({@code application.yaml} 의 {@code logging.pattern.level}).
 * 구조화 로그(JSON)를 켜면 MDC 키가 필드로 그대로 실린다.</p>
 *
 * <p>실행기 스레드는 풀로 돌아가 다음 작업을 받으므로 닫을 때 이전 값으로 되돌린다.</p>
 */
public final class WorkLogContext implements AutoCloseable {

    public static final String TASK_ID = "taskId";
    public static final String DEPLOYMENT_ID = "deploymentId";

    private final String key;
    private final String previous;

    private WorkLogContext(String key, String previous) {
        this.key = key;
        this.previous = previous;
    }

    public static WorkLogContext task(String taskId) {
        return open(TASK_ID, taskId);
    }

    public static WorkLogContext deployment(Long historyId) {
        return open(DEPLOYMENT_ID, historyId == null ? null : historyId.toString());
    }

    private static WorkLogContext open(String key, String value) {
        String previous = MDC.get(key);
        if (value != null) {
            // 값은 서버가 만든 식별자지만, 로그 한 줄에 그대로 박히므로 줄바꿈은 지운다
            MDC.put(key, value.replaceAll("[\r\n]", "_"));
        }
        return new WorkLogContext(key, previous);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }
}
