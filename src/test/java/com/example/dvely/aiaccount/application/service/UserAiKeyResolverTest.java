package com.example.dvely.aiaccount.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.aiaccount.domain.model.AiProviderCredential;
import com.example.dvely.aiaccount.domain.repository.AiProviderCredentialRepository;
import com.example.dvely.common.exception.AiCredentialNotRegisteredException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 이 클래스가 지키는 것은 하나다 — <b>서버 키로 도는 경로가 없다</b>(#364).
 *
 * <p>공개 운영 중에 로그인한 누구나 운영자 계정에 과금할 수 있었던 원인이 "키를 구하는 방법이 두
 * 가지"였다는 데 있다. 지금은 여기 하나뿐이고, 여기에는 배포 설정으로 떨어지는 갈래가 없다.</p>
 */
class UserAiKeyResolverTest {

    private static final Long USER_ID = 7L;
    private static final String ANTHROPIC_KEY = "sk-ant-api03-userkey";
    private static final String OPENAI_KEY = "sk-proj-userkey";

    private AiProviderCredentialRepository repository;
    private UserAiKeyResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(AiProviderCredentialRepository.class);
        resolver = new UserAiKeyResolver(repository);
    }

    @Test
    void 벤더를_지정하면_그_벤더의_사용자_키를_돌려준다() {
        registered(AiProvider.ANTHROPIC, ANTHROPIC_KEY);

        assertThat(resolver.require(USER_ID, AiProvider.ANTHROPIC)).isEqualTo(ANTHROPIC_KEY);
    }

    /**
     * 코딩 에이전트는 자기 이름의 크리덴셜을 갖지 않는다. 사용자는 벤더 키를 한 번만 붙여넣고,
     * CLI 모드와 chat-completions 모드가 그 하나를 함께 쓴다.
     */
    @Test
    void 코딩_에이전트는_대응_벤더의_키로_조회된다() {
        registered(AiProvider.ANTHROPIC, ANTHROPIC_KEY);
        registered(AiProvider.OPENAI, OPENAI_KEY);

        assertThat(resolver.require(USER_ID, AiProvider.CLAUDE_CODE)).isEqualTo(ANTHROPIC_KEY);
        assertThat(resolver.require(USER_ID, AiProvider.CODEX)).isEqualTo(OPENAI_KEY);
    }

    @Test
    void 키를_등록하지_않았으면_실행하지_않고_등록하라고_알린다() {
        when(repository.findByUserIdAndProvider(USER_ID, AiProvider.ANTHROPIC))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.require(USER_ID, AiProvider.CLAUDE_CODE))
                .isInstanceOf(AiCredentialNotRegisteredException.class)
                .hasMessageContaining("ANTHROPIC");
    }

    /**
     * userId 가 없는 호출은 예전 같으면 배포 키로 조용히 실행됐다. 이제는 그럴 키가 없으므로,
     * 조용한 NullPointerException 대신 "서버 키로 도는 경로는 없다"고 말하며 멈춘다.
     */
    @Test
    void 사용자를_모르는_호출은_서버_키로_떨어지지_않고_거절된다() {
        assertThatThrownBy(() -> resolver.require(null, AiProvider.GLM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("서버 키로 도는 경로는 없습니다");
    }

    private void registered(AiProvider vendor, String apiKey) {
        when(repository.findByUserIdAndProvider(USER_ID, vendor))
                .thenReturn(Optional.of(new AiProviderCredential(USER_ID, vendor, apiKey, null)));
    }
}
