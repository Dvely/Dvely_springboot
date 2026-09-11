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
    private FailureAnalysis failureAnalysis = new FailureAnalysis();

    /**
     * 도구를 쓰지 않는 한 번짜리 완성 호출의 출력 상한(Anthropic {@code max_tokens}).
     *
     * <p>예전 값 1024 는 계획 수립에서 실제로 짧았다 — 다단계 계획 JSON 이 중간에서 잘리면
     * {@code DecisionAgentService#retryOnce} 가 전체 컨텍스트에 실패 응답 에코(최대 2KB)까지
     * 얹어 한 번 더 호출한다. 즉 짧은 상한이 아끼는 것은 출력 토큰 몇백이고, 치르는 것은
     * 입력 전체를 한 번 더 보내는 비용이다.</p>
     *
     * <p>상한이지 할당량이 아니다 — 모델이 짧게 답하면 그만큼만 과금된다.</p>
     */
    private int completionMaxTokens = 4096;

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

        /**
         * Messages API 전체 URL(호스트 접두사가 아니다 — 클라이언트가 이 값에 그대로 POST 한다).
         *
         * <p>{@link Glm#getBaseUrl()} 와 같은 이유로 설정 가능하다: 사내 프록시나 게이트웨이를
         * 거치도록 배포를 바꿀 수 있어야 하고, 무엇보다 <b>나가는 요청 본문을 실제로 검사하는
         * 테스트</b>가 가능해진다. 캐시 브레이크포인트 위치처럼 조용히 틀려도 오류가 나지 않는
         * 것(요청은 통과하고 캐시만 안 걸린다)은 본문을 직접 보는 것 말고 검증할 방법이 없다.</p>
         */
        private String baseUrl = "https://api.anthropic.com/v1/messages";

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

        /**
         * 태스크 하나가 쓸 수 있는 누적 토큰 상한(입력 + 출력 + 캐시). 0 이면 상한 없음.
         *
         * <p>이 값이 없던 동안 곱셈이 그대로 열려 있었다 — 제공자 재시도({@code retry.maxAttempts}
         * 3) × 라운드({@link #maxIterations} 40) × 태스크 재시도(3). 각 단계는 자기 한도를 지키지만
         * 태스크 전체가 쓰는 양에는 아무 한도가 없었고, 한 번 헤매기 시작한 태스크가 얼마까지
         * 쓸 수 있는지 아무도 답할 수 없었다.</p>
         *
         * <p>기본값은 정상 완주를 막지 않는 선이다. 라운드마다 트랜스크립트 전체가 다시 실리므로
         * 성공하는 CODE 태스크도 누적 수십만 토큰을 쓴다 — 여유를 세 배쯤 둔 값이고, 걸리는 것은
         * 끝나지 않고 도는 태스크다. 상한에 걸리면 태스크는 사유가 보이는 실패로 닫힌다
         * ({@link com.example.dvely.agent.application.exception.AgentTokenBudgetExceededException}).</p>
         */
        private long maxTaskTokens = 1_000_000;
    }

    /**
     * 배포 실패 로그 요약 전용 설정.
     *
     * <p>이 호출은 제공자가 {@code ANTHROPIC} 으로, 모델이 그 제공자의 기본값(최상위 모델)로
     * 하드코딩돼 있었다. {@link #defaultProvider} 는 GLM 인데 이 한 경로만 그것을 무시했고,
     * 12,000자 로그를 한 번 요약하는 데 최상위 모델을 쓸 근거도 없었다.</p>
     *
     * <p>다만 <b>품질이 떨어지면 실패 분석 자체가 쓸모없어진다.</b> 그래서 코드에 새 값을 박는
     * 대신 설정으로 뺐다 — 분석이 나빠지면 {@code provider: ANTHROPIC} 과 그 모델명을 도로 적어
     * 배포만으로 되돌릴 수 있다.</p>
     */
    @Getter
    @Setter
    public static class FailureAnalysis {

        /** 비우면 {@link AiProperties#defaultProvider} 를 따른다. */
        private AiProvider provider;

        /** 비우면 제공자의 기본 모델을 쓴다. */
        private String model = "";

        public AiProvider providerOr(AiProvider fallback) {
            return provider == null ? fallback : provider;
        }

        /** {@code AiModelOptions.model} 에 그대로 들어간다 — null 이면 제공자 기본 모델이 선택된다. */
        public String modelOrNull() {
            return model == null || model.isBlank() ? null : model.trim();
        }
    }

    /** 실패 분석이 실제로 쓸 제공자. 전용 설정이 없으면 배포의 기본 제공자를 따른다. */
    public AiProvider failureAnalysisProvider() {
        return failureAnalysis.providerOr(defaultProvider);
    }

    /** 제공자별 설정 블록. 코딩 에이전트는 자체 설정이 없으므로 조용히 돌려주지 않고 던진다. */
    public Provider providerConfig(AiProvider provider) {
        return switch (provider) {
            case ANTHROPIC -> anthropic;
            case OPENAI -> openai;
            case GLM -> glm;
            case CLAUDE_CODE, CODEX -> throw new IllegalArgumentException(
                    "코딩 에이전트 제공자는 qeploy.ai.* 설정을 갖지 않습니다: " + provider);
        };
    }
}
