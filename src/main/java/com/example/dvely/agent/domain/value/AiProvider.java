package com.example.dvely.agent.domain.value;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What runs a request.
 *
 * <p>Two kinds live in this one enum because the user picks between them in one place ("which
 * engine should do this?"):</p>
 * <ul>
 *   <li><b>Vendors</b> — {@link #ANTHROPIC}, {@link #OPENAI}, {@link #GLM}: a chat-completions
 *       endpoint we drive through our own tool loop, on the deployment's configured key.</li>
 *   <li><b>Coding agents</b> — {@link #CLAUDE_CODE}, {@link #CODEX}: the vendor's official CLI run
 *       headlessly on the <i>end user's own</i> key (BYOK), where the vendor's binary owns the
 *       agentic loop rather than we do.</li>
 * </ul>
 *
 * <p>A coding agent is not a separate credential: Claude Code authenticates with the Anthropic key
 * and Codex with the OpenAI key, which is why {@link #credentialVendor()} exists and why the
 * credential store is keyed by vendor — a user pastes each key once.</p>
 */
@Schema(description = """
        사용할 AI 제공자.
        ANTHROPIC = Claude, OPENAI = GPT, GLM = OpenRouter 경유 GLM (배포 설정 키로 동작),
        CLAUDE_CODE = Claude Code CLI, CODEX = Codex CLI (사용자 본인 API 키로 동작하는 코딩 에이전트)
        """)
public enum AiProvider {

    ANTHROPIC,
    OPENAI,
    GLM,

    // Appended below the vendors deliberately: these are execution modes, and several exhaustive
    // switches over this enum treat them as "not a chat-completions provider".
    CLAUDE_CODE,
    CODEX;

    /** True for the official-CLI execution modes, which run on the end user's own key. */
    public boolean isCodingAgent() {
        return this == CLAUDE_CODE || this == CODEX;
    }

    /**
     * The vendor whose credential this provider authenticates with. Vendors map to themselves;
     * a coding agent maps to the vendor whose key its CLI reads.
     *
     * <p>This is the single place that knows the mapping, so "one key per vendor per user" stays
     * true no matter how many execution modes are added later.</p>
     */
    public AiProvider credentialVendor() {
        return switch (this) {
            case CLAUDE_CODE -> ANTHROPIC;
            case CODEX -> OPENAI;
            case ANTHROPIC, OPENAI, GLM -> this;
        };
    }
}
