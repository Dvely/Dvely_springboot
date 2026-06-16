package com.example.dvely.agent.application.dto;

public record AgentTaskFailure(
        String logExcerpt,
        String suggestedFix,
        int attempt,
        int maxAttempts
) {
    public boolean retryable() {
        return attempt < maxAttempts;
    }
}
