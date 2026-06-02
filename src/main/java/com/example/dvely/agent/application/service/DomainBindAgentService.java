package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.agent.infrastructure.store.InputWaitStore;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.domainbinding.application.command.dto.BindDomainCommand;
import com.example.dvely.domainbinding.application.facade.DomainBindingFacade;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.application.result.VerificationGuideResult;
import com.example.dvely.domainbinding.domain.value.DomainType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class DomainBindAgentService {

    private static final long INPUT_TIMEOUT = 5L; // 분

    private final DomainBindingFacade domainBindingFacade;
    private final TaskStore           taskStore;
    private final InputWaitStore      inputWaitStore;

    public CodeResult execute(AgentStep step, Long userId, String taskId, Long projectId) {
        log.info("[DomainBindAgent] 도메인 연결 시작 | userId={} taskId={} projectId={}", userId, taskId, projectId);

        if (projectId == null) {
            throw new IllegalStateException("도메인 연결은 배포된 프로젝트에만 가능합니다. projectId가 필요합니다.");
        }

        String domain = step.parameters().getOrDefault("domain", "").trim();
        if (domain.isEmpty()) {
            domain = askUserForDomain(userId, taskId);
        }

        BindDomainCommand command = buildCommand(domain);
        log.info("[DomainBindAgent] 도메인 연결 요청 | domain={} type={}", domain, command.type());

        DomainBindingResult result = domainBindingFacade.bindDomain(userId, projectId, command);
        String summary = buildSummary(result, userId, projectId);

        log.info("[DomainBindAgent] 도메인 연결 완료 | hostname={} status={}", result.hostname(), result.status());
        return new CodeResult(null, summary);
    }

    // ── 도메인 타입 판별 및 커맨드 생성 ────────────────────────────────────────

    private BindDomainCommand buildCommand(String domain) {
        if (domain.contains(".")) {
            // 점이 있으면 커스텀 도메인 (예: www.mysite.com)
            return new BindDomainCommand(DomainType.CUSTOM_DOMAIN, null, domain, null);
        } else {
            // 점이 없으면 관리형 서브도메인 라벨 (예: my-app → my-app.dvely.app)
            return new BindDomainCommand(DomainType.MANAGED_SUBDOMAIN, domain, null, null);
        }
    }

    // ── 사용자 입력 대기 ────────────────────────────────────────────────────────

    private String askUserForDomain(Long userId, String taskId) {
        String question = "연결할 도메인을 입력해주세요.\n"
                + "- 관리형 서브도메인: 라벨만 입력 (예: my-app → my-app.qeploy.com)\n"
                + "- 커스텀 도메인: 전체 주소 입력 (예: www.mysite.com)";

        taskStore.markWaitingInput(taskId, question);
        log.info("[DomainBindAgent] 사용자 입력 대기 중 | taskId={}", taskId);

        CompletableFuture<String> future = inputWaitStore.register(taskId);
        try {
            String answer = future.get(INPUT_TIMEOUT, TimeUnit.MINUTES);
            String domain = answer.trim();
            if (domain.isEmpty()) throw new IllegalArgumentException("도메인 값이 비어 있습니다.");
            return domain;
        } catch (TimeoutException e) {
            inputWaitStore.cancel(taskId);
            throw new IllegalStateException("도메인 입력 대기 시간이 초과되었습니다.");
        } catch (ExecutionException e) {
            throw new IllegalStateException("도메인 입력 처리 중 오류: " + e.getCause().getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("도메인 입력 대기 중 인터럽트 발생");
        } finally {
            taskStore.save(taskStore.get(taskId)
                    .withStatus(TaskStatus.RUNNING, null, null, null));
        }
    }

    // ── 요약 생성 ──────────────────────────────────────────────────────────────

    private String buildSummary(DomainBindingResult result, Long userId, Long projectId) {
        return switch (result.type()) {
            case MANAGED_SUBDOMAIN -> String.format("""
                    관리형 서브도메인 연결 완료
                    - 도메인: %s
                    - 상태: %s
                    - DNS가 전파되면 수 분 내 접근 가능합니다.
                    """, result.hostname(), result.status());

            case CUSTOM_DOMAIN -> {
                VerificationGuideResult guide = fetchGuide(userId, result.domainId());
                String recordInfo = guide != null ? buildRecordInfo(guide) : "";
                yield String.format("""
                        커스텀 도메인 연결 요청 완료
                        - 도메인: %s
                        - 상태: DNS 검증 대기 중
                        %s
                        DNS 설정 후 검증 요청을 보내주세요.
                        """, result.hostname(), recordInfo);
            }

            default -> String.format("도메인 연결 완료: %s (상태: %s)", result.hostname(), result.status());
        };
    }

    private VerificationGuideResult fetchGuide(Long userId, Long domainId) {
        try {
            return domainBindingFacade.getVerificationGuide(userId, domainId);
        } catch (Exception e) {
            log.warn("[DomainBindAgent] 검증 가이드 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    private String buildRecordInfo(VerificationGuideResult guide) {
        StringBuilder sb = new StringBuilder("- DNS 설정 정보:\n");
        for (var record : guide.records()) {
            sb.append(String.format("  %s %s → %s\n", record.type(), record.host(), record.value()));
        }
        return sb.toString();
    }
}
