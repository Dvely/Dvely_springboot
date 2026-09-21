package com.example.dvely.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.port.out.LlmMessage;
import com.example.dvely.agent.domain.value.AiModelOptions;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.application.service.UserAiKeyResolver;
import com.example.dvely.common.exception.AiCredentialNotRegisteredException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 라우팅이 지켜야 할 두 가지.
 *
 * <p><b>하나 — 호출은 호출자 본인의 키로 나간다.</b> 배포는 벤더 키를 갖지 않으므로 여기서 키를
 * 얻지 못하면 호출 자체가 성립하지 않는다(#364).</p>
 *
 * <p><b>둘 — 코딩 에이전트도 계획·채팅을 탈 수 있다.</b> 예전에는 {@code CLAUDE_CODE}/{@code CODEX}
 * 가 이 라우터에서 예외로 떨어졌다. 그런데 FE 가 (서버 키 제공자를 화면에서 걷어내면서) 고를 수
 * 있는 것을 코딩 에이전트만 남기자, 사용자가 무엇을 입력하든 DECISION 단계에서 죽었다 — CODE 는
 * 벤더 CLI 가 맡지만 계획과 채팅은 chat-completions 가 필요하고, 그 키는 사용자가 이미 등록해 둔
 * 같은 벤더 키다. 이 파일은 그 경로가 다시 막히지 않게 한다.</p>
 */
class LlmRouterByokTest {

    private static final Long USER_ID = 7L;
    private static final String ANTHROPIC_KEY = "sk-ant-api03-userkey";
    private static final String OPENAI_KEY = "sk-proj-userkey";
    private static final String GLM_KEY = "sk-or-userkey";

    private static final List<LlmMessage> MESSAGES = List.of(new LlmMessage("user", "안녕"));

    private ClaudeClient claudeClient;
    private OpenAiClient openAiClient;
    private GlmClient glmClient;
    private UserAiKeyResolver keyResolver;
    private LlmRouter router;

    @BeforeEach
    void setUp() {
        claudeClient = mock(ClaudeClient.class);
        openAiClient = mock(OpenAiClient.class);
        glmClient = mock(GlmClient.class);
        keyResolver = mock(UserAiKeyResolver.class);
        router = new LlmRouter(claudeClient, openAiClient, glmClient, keyResolver);

        when(keyResolver.require(USER_ID, AiProvider.ANTHROPIC)).thenReturn(ANTHROPIC_KEY);
        when(keyResolver.require(USER_ID, AiProvider.CLAUDE_CODE)).thenReturn(ANTHROPIC_KEY);
        when(keyResolver.require(USER_ID, AiProvider.OPENAI)).thenReturn(OPENAI_KEY);
        when(keyResolver.require(USER_ID, AiProvider.CODEX)).thenReturn(OPENAI_KEY);
        when(keyResolver.require(USER_ID, AiProvider.GLM)).thenReturn(GLM_KEY);
    }

    @Test
    void 벤더_요청은_그_사용자의_키로_나간다() {
        router.route(AiProvider.ANTHROPIC, USER_ID).complete("sys", MESSAGES, AiModelOptions.defaults());

        verify(claudeClient).complete(eq("sys"), anyList(), any(), eq(ANTHROPIC_KEY));
    }

    /**
     * 이 테스트가 깨지면 운영에서 "무엇을 입력해도 계획 단계에서 실패한다"가 된다 — FE 가 고를 수
     * 있게 내놓는 것이 코딩 에이전트뿐이기 때문이다.
     */
    @Test
    void 코딩_에이전트도_계획_채팅_경로를_탄다() {
        assertThatCode(() ->
                router.route(AiProvider.CLAUDE_CODE, USER_ID)
                        .complete("sys", MESSAGES, AiModelOptions.defaults()))
                .doesNotThrowAnyException();

        verify(claudeClient).complete(anyString(), anyList(), any(), eq(ANTHROPIC_KEY));
    }

    @Test
    void CODEX_는_OpenAI_경로로_그_사용자의_키를_들고_간다() {
        router.route(AiProvider.CODEX, USER_ID).complete("sys", MESSAGES, AiModelOptions.defaults());

        verify(openAiClient).complete(anyString(), anyList(), any(), eq(OPENAI_KEY));
    }

    @Test
    void GLM_요청은_GLM_클라이언트로_간다() {
        router.route(AiProvider.GLM, USER_ID).complete("sys", MESSAGES, AiModelOptions.defaults());

        verify(glmClient).complete(anyString(), anyList(), any(), eq(GLM_KEY));
    }

    /**
     * 키는 포트를 만들 때 풀린다 — 호출 직전이 아니라. 등록되지 않은 사용자의 요청이 LLM 왕복을
     * 시작조차 하지 못해야 과금이 일어나지 않는다.
     */
    @Test
    void 키가_없으면_포트를_얻는_시점에_거절된다() {
        when(keyResolver.require(USER_ID, AiProvider.CLAUDE_CODE))
                .thenThrow(new AiCredentialNotRegisteredException("ANTHROPIC API 키가 등록되지 않았습니다."));

        assertThatThrownBy(() -> router.route(AiProvider.CLAUDE_CODE, USER_ID))
                .isInstanceOf(AiCredentialNotRegisteredException.class);
    }

    /** 한 사용자의 키가 다른 사용자의 호출에 실리지 않는다 — 포트는 호출마다 새로 묶인다. */
    @Test
    void 서로_다른_사용자의_키가_섞이지_않는다() {
        Long otherUser = 8L;
        String otherKey = "sk-ant-api03-otheruser";
        when(keyResolver.require(otherUser, AiProvider.ANTHROPIC)).thenReturn(otherKey);

        var mine = router.route(AiProvider.ANTHROPIC, USER_ID);
        var theirs = router.route(AiProvider.ANTHROPIC, otherUser);

        mine.complete("sys", MESSAGES, AiModelOptions.defaults());
        theirs.complete("sys", MESSAGES, AiModelOptions.defaults());

        verify(claudeClient).complete(anyString(), anyList(), any(), eq(ANTHROPIC_KEY));
        verify(claudeClient).complete(anyString(), anyList(), any(), eq(otherKey));
        assertThat(mine).isNotSameAs(theirs);
    }
}
