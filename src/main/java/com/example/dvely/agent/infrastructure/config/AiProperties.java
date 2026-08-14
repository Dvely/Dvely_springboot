package com.example.dvely.agent.infrastructure.config;

import java.util.List;
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

    /**
     * Settings shared by every provider. Clients may now ask for a specific model and for extended
     * thinking, so each provider needs to declare what it will actually accept — an unrestricted
     * model parameter would let a request name any model at all, including ones that do not exist
     * or cost far more per call than the deployment budgeted for.
     */
    @Getter
    @Setter
    public abstract static class Provider {

        private String apiKey;

        /** Model used when a request does not name one. Always accepted, whatever the lists say. */
        private String model;

        /**
         * Models a request may name in addition to {@link #model}. Empty means the configured
         * model is the only choice — the safe default, since widening it is a cost decision.
         */
        private List<String> allowedModels = List.of();

        /**
         * Models that accept a thinking/reasoning parameter. Asking for thinking on a model
         * outside this list is rejected rather than silently dropped: a request that quietly
         * ignores the setting looks identical to one that honoured it, and the caller would be
         * paying attention to a control that does nothing.
         */
        private List<String> thinkingModels = List.of();

        protected Provider(String defaultModel) {
            this.model = defaultModel;
        }

        public boolean allows(String candidateModel) {
            return candidateModel.equals(model) || allowedModels.contains(candidateModel);
        }

        public boolean supportsThinking(String candidateModel) {
            return thinkingModels.contains(candidateModel);
        }
    }

    @Getter
    @Setter
    public static class Anthropic extends Provider {
        public Anthropic() {
            super("claude-opus-4-5-20251101");
        }
    }

    @Getter
    @Setter
    public static class Openai extends Provider {
        public Openai() {
            super("gpt-4o");
        }
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
