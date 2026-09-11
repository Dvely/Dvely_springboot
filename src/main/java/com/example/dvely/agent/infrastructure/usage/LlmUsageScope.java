package com.example.dvely.agent.infrastructure.usage;

import com.example.dvely.agent.application.exception.AgentTokenBudgetExceededException;
import com.example.dvely.agent.domain.value.LlmUsage;

/**
 * "지금 도는 이 LLM 호출은 누구의 어느 작업 것인가" 를 실행 스레드에 붙여 두는 자리.
 *
 * <p>대안은 {@code LlmPort}/{@code LlmToolPort} 시그니처에 taskId 를 더하는 것이었다. 그러면
 * 호출부가 전부 바뀌는데, 그 파일들은 다른 작업자가 동시에 손대는 파일이라 충돌 비용이 계측
 * 가치보다 커진다. 게다가 시그니처에 실으면 <b>새로 생기는 호출부가 빠뜨릴 수 있다</b> —
 * 스코프 방식은 호출부가 무엇을 하든 제공자 클라이언트가 알아서 센다.</p>
 *
 * <p>스코프가 열려 있지 않은 호출도 기록은 된다({@link LlmUsagePhase#UNSCOPED}). 귀속만 없을
 * 뿐 합계에서 사라지지 않아야, "합계가 맞는가" 를 나중에 의심하지 않아도 된다.</p>
 *
 * <p><b>스레드를 넘지 않는다.</b> {@code @Async} 로 다른 스레드에 넘어가는 구간은 그 스레드에서
 * 다시 열어야 한다 — 상속되는 ThreadLocal 로 만들면 스레드풀이 스코프를 재사용해 남의 작업
 * 토큰이 엉뚱한 태스크에 붙는다.</p>
 */
public final class LlmUsageScope implements AutoCloseable {

    private static final ThreadLocal<LlmUsageScope> CURRENT = new ThreadLocal<>();

    private final LlmUsageScope previous;
    private final String taskId;
    private final Long userId;
    private final Long projectId;
    private final LlmUsagePhase phase;
    /** 0 이면 상한 없음. */
    private final long budgetTokens;

    private long usedTokens;
    private boolean closed;

    private LlmUsageScope(LlmUsageScope previous,
                          String taskId,
                          Long userId,
                          Long projectId,
                          LlmUsagePhase phase,
                          long budgetTokens) {
        this.previous = previous;
        this.taskId = taskId;
        this.userId = userId;
        this.projectId = projectId;
        this.phase = phase == null ? LlmUsagePhase.UNSCOPED : phase;
        this.budgetTokens = Math.max(0, budgetTokens);
    }

    /** 상한 없는 스코프. 계획 수립·실패 분석처럼 호출이 유계인 구간에 쓴다. */
    public static LlmUsageScope open(String taskId, Long userId, Long projectId, LlmUsagePhase phase) {
        return open(taskId, userId, projectId, phase, 0);
    }

    public static LlmUsageScope open(String taskId,
                                     Long userId,
                                     Long projectId,
                                     LlmUsagePhase phase,
                                     long budgetTokens) {
        return open(taskId, userId, projectId, phase, budgetTokens, 0);
    }

    /**
     * @param alreadyUsedTokens 이 태스크가 이전 실행(재시도 전)에 이미 쓴 토큰. 0 에서 다시 세면
     *                          상한이 태스크 재시도 횟수만큼 곱해져 상한이 아니게 된다
     */
    public static LlmUsageScope open(String taskId,
                                     Long userId,
                                     Long projectId,
                                     LlmUsagePhase phase,
                                     long budgetTokens,
                                     long alreadyUsedTokens) {
        LlmUsageScope scope =
                new LlmUsageScope(CURRENT.get(), taskId, userId, projectId, phase, budgetTokens);
        scope.usedTokens = Math.max(0, alreadyUsedTokens);
        CURRENT.set(scope);
        return scope;
    }

    public static LlmUsageScope current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public String taskId() {
        return taskId;
    }

    public Long userId() {
        return userId;
    }

    public Long projectId() {
        return projectId;
    }

    public LlmUsagePhase phase() {
        return phase;
    }

    public long usedTokens() {
        return usedTokens;
    }

    public long budgetTokens() {
        return budgetTokens;
    }

    /**
     * 이번 호출분을 누적하고, 상한을 넘었으면 던진다.
     *
     * <p>던지기 <b>전에</b> 누적한다. 상한을 터뜨린 그 호출도 이미 과금됐으므로 합계에 들어가야
     * 하고, 그래야 사용자에게 보이는 수치가 실제 청구와 어긋나지 않는다.</p>
     */
    synchronized void accumulate(LlmUsage usage) {
        usedTokens += usage.totalTokens();
        if (budgetTokens > 0 && usedTokens > budgetTokens) {
            throw new AgentTokenBudgetExceededException(usedTokens, budgetTokens);
        }
    }
}
