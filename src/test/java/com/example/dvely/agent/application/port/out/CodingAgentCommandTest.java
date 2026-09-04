package com.example.dvely.agent.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CodingAgentCommandTest {

    private static final String API_KEY = "sk-ant-api03-secretvalue";

    private static CodingAgentCommand command() {
        return new CodingAgentCommand("빌드 로그를 분석해줘", "/workspace/app", API_KEY, Duration.ofMinutes(5));
    }

    @Test
    void toStringRedactsTheApiKey() {
        // A record's generated toString() would print every component, so any log line formatting
        // this object would emit the user's real credential. The override must survive refactors.
        assertThat(command().toString()).doesNotContain(API_KEY);
        assertThat(command().toString()).contains("apiKey=***");
    }

    @Test
    void toStringDoesNotPrintThePromptBody() {
        // Prompts can carry source code and build output; length is enough for diagnostics.
        assertThat(command().toString()).doesNotContain("빌드 로그를 분석해줘");
        assertThat(command().toString()).contains("promptLength=");
    }

    @Test
    void toStringKeepsTheWorkspaceForDiagnostics() {
        assertThat(command().toString()).contains("/workspace/app");
    }

    @Test
    void rejectsABlankPrompt() {
        assertThatThrownBy(() -> new CodingAgentCommand("  ", "/workspace", API_KEY, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankWorkspaceDir() {
        assertThatThrownBy(() -> new CodingAgentCommand("do it", " ", API_KEY, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankApiKey() {
        assertThatThrownBy(() -> new CodingAgentCommand("do it", "/workspace", " ", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANonPositiveTimeout() {
        // A zero/negative bound would mean "kill immediately" or "never" depending on the runner —
        // neither is a sane contract, so it is rejected at construction.
        assertThatThrownBy(() -> new CodingAgentCommand("do it", "/workspace", API_KEY, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
