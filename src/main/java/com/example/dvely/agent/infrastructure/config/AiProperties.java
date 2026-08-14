package com.example.dvely.agent.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "qeploy.ai")
@Getter
@Setter
public class AiProperties {

    private Anthropic anthropic = new Anthropic();
    private Openai openai = new Openai();
    private CodeAgent codeAgent = new CodeAgent();

    @Getter
    @Setter
    public static class Anthropic {
        private String apiKey;
        private String model = "claude-opus-4-5-20251101";
    }

    @Getter
    @Setter
    public static class Openai {
        private String apiKey;
        private String model = "gpt-4o";
    }

    @Getter
    @Setter
    public static class CodeAgent {
        /**
         * LLM round budget for a single CODE step's tool loop (one round = one LLM call plus the
         * tool calls it emits). Unlike the Docker-side constants this one is a real configuration
         * surface: the previous hard-coded 20 was exhausted by ordinary scaffold → implement →
         * build runs, and how many rounds a project actually needs varies with the request, so an
         * operator has to be able to raise it without a redeploy.
         *
         * <p>Exhausting it is a failure ({@link
         * com.example.dvely.agent.application.exception.AgentIterationLimitException}), and the
         * recovery path retries in the same container — so the effective ceiling for one task is
         * this value times the task's retry budget, not this value alone.</p>
         */
        private int maxIterations = 40;
    }
}
