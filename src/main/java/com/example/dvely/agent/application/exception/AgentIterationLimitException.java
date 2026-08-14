package com.example.dvely.agent.application.exception;

/**
 * Raised when a CODE agent tool loop exhausts its per-run LLM round budget without the model ever
 * signalling completion (a text-only response).
 *
 * <p>This used to be a plain {@code return "최대 반복 횟수 도달로 작업이 종료되었습니다."}, which made an
 * unfinished run indistinguishable from a successful one: the sentence became the step summary, so
 * {@code AgentPlanExecutor} called {@code markDone} and posted it as the assistant's chat reply
 * while the preview served a half-built (or entirely unbuilt) workspace. Exhaustion is a failure —
 * modelling it as one routes it through {@code CodeAgentExecutionException} and the existing
 * build-failure recovery path, which resumes the same container ({@code PreviewSessionService#acquire}
 * reuses a running session for the task) with the remaining work as the instruction.</p>
 */
public class AgentIterationLimitException extends RuntimeException {

    private final int maxIterations;
    private final String progressLog;

    public AgentIterationLimitException(int maxIterations, String progressLog) {
        super("LLM 반복 한도(" + maxIterations + "회) 내에 작업이 끝나지 않았습니다.");
        this.maxIterations = maxIterations;
        this.progressLog = progressLog;
    }

    public int maxIterations() {
        return maxIterations;
    }

    /** Compact trace of the last few tool calls — surfaced to the user as the failure's log excerpt. */
    public String progressLog() {
        return progressLog;
    }
}
