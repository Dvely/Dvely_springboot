package com.example.dvely.aiaccount.application.service;

import com.example.dvely.agent.application.port.out.CodingAgentCommand;
import com.example.dvely.agent.application.port.out.CodingAgentResult;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.agent.infrastructure.codingagent.CodingAgentProperties;
import com.example.dvely.agent.infrastructure.codingagent.CodingAgentRouter;
import com.example.dvely.aiaccount.domain.model.AiProviderCredential;
import com.example.dvely.aiaccount.domain.repository.AiProviderCredentialRepository;
import com.example.dvely.common.exception.AiCredentialNotRegisteredException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Runs a coding agent for one user, on that user's own key.
 *
 * <p>This is the seam where BYOK actually becomes a rule rather than an intention: the key is
 * looked up by {@code (userId, vendor)} and there is no fallback to a deployment-wide key. A user
 * without a registered key gets a clear "register one" error instead of quietly spending the
 * operator's credit — which is the behaviour the providers' terms require, not merely a nicety.</p>
 *
 * <p>여기에 {@code @Transactional} 을 두지 않는 이유: 아래 {@code run()} 은 컨테이너 안의 CLI 가
 * 끝날 때까지 최대 10분을 기다린다. 트랜잭션을 걸면 그 10분 내내 커넥션 하나가 묶이고, 동시
 * CODE 태스크 수만큼 곱해져 풀이 마른다(2026-09-08 dev 고갈 사고와 같은 계열, #337). 이 메서드가
 * DB 에서 하는 일은 자격증명 조회 한 건뿐이라 리포지토리 자신의 짧은 트랜잭션으로 충분하고,
 * {@code AiProviderCredential} 은 JPA 엔티티가 아닌 도메인 모델이라 반환 뒤 세션이 필요 없다.
 * {@code AgentPlanExecutor.execute()}·{@code InfraOpsAgentService} 와 같은 원칙이다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodingAgentExecutionService {

    private final AiProviderCredentialRepository credentialRepository;
    private final CodingAgentRouter router;
    private final CodingAgentProperties properties;

    /**
     * @param provider      a coding-agent provider ({@code CLAUDE_CODE} / {@code CODEX})
     * @param workspaceDir  absolute host path of the checkout the agent may edit
     */
    public CodingAgentResult run(Long userId, AiProvider provider, String prompt, String workspaceDir) {
        return run(userId, provider, prompt, workspaceDir, properties.getTimeout());
    }

    public CodingAgentResult run(Long userId,
                                 AiProvider provider,
                                 String prompt,
                                 String workspaceDir,
                                 Duration timeout) {
        if (!provider.isCodingAgent()) {
            throw new IllegalArgumentException("코딩 에이전트 제공자가 아닙니다: " + provider);
        }

        // The execution mode is converted to its vendor here and nowhere else — CLAUDE_CODE reads
        // the ANTHROPIC key, CODEX the OPENAI one, so the user registers each key once.
        AiProvider vendor = provider.credentialVendor();
        AiProviderCredential credential = credentialRepository
                .findByUserIdAndProvider(userId, vendor)
                .orElseThrow(() -> new AiCredentialNotRegisteredException(
                        vendor + " API 키가 등록되지 않았습니다. 설정에서 본인 키를 먼저 등록해주세요."));

        log.info("코딩 에이전트 실행 | userId={} provider={} vendor={}", userId, provider, vendor);

        // The plaintext key lives only in this command, whose toString() redacts it.
        CodingAgentCommand command =
                new CodingAgentCommand(prompt, workspaceDir, credential.getApiKey(), timeout);

        return router.route(provider).run(command);
    }
}
