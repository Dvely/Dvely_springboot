package com.example.dvely.agent.infrastructure.llm;

import com.example.dvely.agent.application.port.out.LlmPort;
import com.example.dvely.agent.domain.value.AiProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LlmRouter {

    private final ClaudeClient claudeClient;
    private final OpenAiClient openAiClient;
    private final GlmClient    glmClient;

    public LlmPort route(AiProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> claudeClient;
            case OPENAI    -> openAiClient;
            case GLM       -> glmClient;
            // Coding agents have no chat-completions endpoint to route to — the vendor's CLI owns
            // the loop, so they are served by CodingAgentRouter instead. Failing loudly here beats
            // a default branch that would quietly hand back Claude for a CODEX request.
            case CLAUDE_CODE, CODEX -> throw new IllegalArgumentException(
                    "코딩 에이전트 제공자는 채팅 완성 경로로 라우팅할 수 없습니다: " + provider);
        };
    }
}
