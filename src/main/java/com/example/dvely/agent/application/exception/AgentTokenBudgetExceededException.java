package com.example.dvely.agent.application.exception;

/**
 * 한 태스크가 쓸 수 있는 누적 토큰 상한을 넘겼다.
 *
 * <p>상한이 없던 동안 곱셈이 그대로 열려 있었다 — 제공자 재시도({@code retry.maxAttempts} 3) ×
 * 라운드({@code codeAgent.maxIterations} 40) × 태스크 재시도({@code maxAttempts} 3). 각 단계는
 * 자기 한도를 지키지만 태스크 전체가 쓰는 양에는 아무 한도가 없었다.</p>
 *
 * <p>메시지는 <b>사용자에게 그대로 보인다.</b> 상한에 걸렸을 때 조용히 멈추면 사용자에게는
 * "왜 안 되지" 로만 남으므로, 무엇이 일어났고 무엇을 하면 되는지를 한 문장으로 담는다.</p>
 */
public class AgentTokenBudgetExceededException extends RuntimeException {

    private final long usedTokens;
    private final long budgetTokens;

    public AgentTokenBudgetExceededException(long usedTokens, long budgetTokens) {
        super(("이 작업이 AI 토큰 예산 상한에 도달해 중단했습니다 (%,d / %,d 토큰). "
                + "요청을 더 작은 단위로 나눠 다시 시도하거나, 관리자에게 상한 조정을 요청해주세요.")
                .formatted(usedTokens, budgetTokens));
        this.usedTokens = usedTokens;
        this.budgetTokens = budgetTokens;
    }

    public long usedTokens() {
        return usedTokens;
    }

    public long budgetTokens() {
        return budgetTokens;
    }
}
