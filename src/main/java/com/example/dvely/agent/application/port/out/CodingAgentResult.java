package com.example.dvely.agent.application.port.out;

/**
 * Outcome of one headless coding-agent run.
 *
 * <p>{@code timedOut} is carried separately from {@code success} rather than folded into a generic
 * failure: a timeout means the work may well have been half-applied to the workspace, so a caller
 * deciding whether it is safe to retry needs to tell it apart from a clean non-zero exit.</p>
 *
 * @param success  true only on a clean zero exit within the timeout
 * @param output   the agent's stdout (its answer / transcript)
 * @param errorOutput the agent's stderr, kept for diagnostics
 * @param exitCode process exit code; {@code -1} when the process was killed on timeout
 * @param timedOut whether the run was killed for exceeding its wall-clock bound
 */
public record CodingAgentResult(
        boolean success,
        String output,
        String errorOutput,
        int exitCode,
        boolean timedOut
) {

    public static CodingAgentResult succeeded(String output, String errorOutput) {
        return new CodingAgentResult(true, output, errorOutput, 0, false);
    }

    public static CodingAgentResult failed(String output, String errorOutput, int exitCode) {
        return new CodingAgentResult(false, output, errorOutput, exitCode, false);
    }

    public static CodingAgentResult timedOut(String output, String errorOutput) {
        return new CodingAgentResult(false, output, errorOutput, -1, true);
    }
}
