package com.example.dvely.agent.infrastructure.config;

import com.example.dvely.agent.domain.value.AiProvider;
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
    private Glm glm = new Glm();
    private Retry retry = new Retry();
    private CodeAgent codeAgent = new CodeAgent();

    /**
     * 요청이 제공자를 지정하지 않을 때 쓰는 기본 제공자. 배포가 유효한 키를 가진 것으로 맞춘다 —
     * 하드코딩된 기본이 크레딧 없는 제공자를 가리키면 지정 안 한 모든 요청이 실패한다(그 문제로
     * ANTHROPIC 고정을 걷어냄). 기본은 GLM(OpenRouter 경유) 이다.
     */
    private AiProvider defaultProvider = AiProvider.GLM;

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

    /**
     * GLM, reached through OpenRouter rather than Z.ai directly. OpenRouter speaks the OpenAI
     * chat-completions format, so the only thing that separates this provider from {@link Openai}
     * at the wire level is where the request is posted and which key signs it — hence the
     * configurable {@link #baseUrl}, which also lets a deployment point the same provider at Z.ai's
     * own OpenAI-compatible endpoint, or at a self-hosted gateway, without a code change.
     */
    @Getter
    @Setter
    public static class Glm extends Provider {

        /**
         * Full chat-completions URL, not a host prefix — the clients post to it verbatim.
         *
         * <p>Repointing this at Z.ai's own endpoint
         * ({@code https://api.z.ai/api/paas/v4/chat/completions}) works, but two things change with
         * it and neither is inferred automatically: the model slugs are unprefixed there
         * ({@code glm-4.6}, not {@code z-ai/glm-4.6}), and Z.ai spells extended thinking as
         * {@code thinking: {type: "enabled"}} rather than OpenRouter's {@code reasoning: {effort}},
         * so {@link #getThinkingModels()} should be left empty for a Z.ai deployment until that
         * dialect is implemented — otherwise a thinking request is accepted and silently ignored.</p>
         */
        private String baseUrl = "https://openrouter.ai/api/v1/chat/completions";

        /**
         * OpenRouter's optional attribution headers ({@code HTTP-Referer}, {@code X-Title}). They
         * are what makes calls identifiable on the OpenRouter dashboard and rankings; blank means
         * the header is simply not sent, which OpenRouter accepts.
         */
        private String referer = "";
        private String title = "";

        public Glm() {
            super("z-ai/glm-4.6");
        }
    }

    /**
     * How hard to try again when a provider call fails for a reason that could clear on its own.
     *
     * <p>Without this a single 429 or 5xx ends the whole task: {@code AgentPlanExecutor} closes a
     * task on {@link com.example.dvely.common.exception.LlmProviderException} without retrying, so
     * the user is told "잠시 후 다시 시도해주세요" by a server that did not itself try again. GLM's
     * free tier makes it routine rather than rare — {@code glm-4.7-flash} answers
     * {@code 429 (code 1305 overloaded)} often enough that a 2026-09-03 실측 needed three attempts
     * to get one response.</p>
     *
     * <p>Only {@link com.example.dvely.common.exception.LlmProviderException#retryable()} reasons
     * are retried. A missing key, a rejected key and an empty balance do not resolve themselves
     * between attempts, so retrying those only delays the message the user needs to see.</p>
     */
    @Getter
    @Setter
    public static class Retry {

        /**
         * Total attempts including the first, so 1 disables retrying. Kept low by default because
         * the CODE agent spends this budget per round, up to maxIterations rounds.
         */
        private int maxAttempts = 3;

        /** Delay before the second attempt; doubles each time, capped by {@link #maxDelayMs}. */
        private long initialDelayMs = 1_000;

        /**
         * Ceiling for one wait. Bounds the worst case an operator has to reason about: with the
         * defaults a call cannot spend more than 1s + 2s waiting before it gives up.
         */
        private long maxDelayMs = 8_000;
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
