package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.exception.AgentInputRequiredException;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.agent.infrastructure.store.InputWaitStore;
import com.example.dvely.domainbinding.application.command.dto.BindDomainCommand;
import com.example.dvely.domainbinding.application.facade.DomainBindingFacade;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.application.result.VerificationGuideResult;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


@Slf4j
@Service
@RequiredArgsConstructor
public class DomainBindAgentService {

    private final DomainBindingFacade domainBindingFacade;
    private final InputWaitStore      inputWaitStore;

    public CodeResult execute(AgentStep step, Long userId, String taskId, Long projectId) {
        log.info("[DomainBindAgent] 도메인 연결 시작 | userId={} taskId={} projectId={}", userId, taskId, projectId);

        if (projectId == null) {
            throw new IllegalStateException("도메인 연결은 배포된 프로젝트에만 가능합니다. projectId가 필요합니다.");
        }

        if ("DELETE".equalsIgnoreCase(step.parameters().getOrDefault("operation", ""))) {
            return deleteDomain(step, userId);
        }

        String domain = step.parameters().getOrDefault("domain", "").trim();
        if (domain.isEmpty()) {
            domain = askUserForDomain(userId, taskId);
        }

        BindDomainCommand command = buildCommand(step, domain);
        log.info("[DomainBindAgent] 도메인 연결 요청 | domain={} type={}", domain, command.type());

        DomainBindingResult result = domainBindingFacade.bindDomain(userId, projectId, command);
        String summary = buildSummary(result, userId, projectId);

        log.info("[DomainBindAgent] 도메인 연결 완료 | hostname={} status={}", result.hostname(), result.status());
        return new CodeResult(null, summary);
    }

    private CodeResult deleteDomain(AgentStep step, Long userId) {
        String domainIdValue = step.parameters().getOrDefault("domainId", "").trim();
        if (domainIdValue.isEmpty()) {
            throw new IllegalArgumentException("삭제할 domainId가 필요합니다.");
        }
        Long domainId;
        try {
            domainId = Long.valueOf(domainIdValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("올바르지 않은 domainId입니다: " + domainIdValue, exception);
        }
        domainBindingFacade.deleteDomain(userId, domainId);
        String hostname = step.parameters().getOrDefault("domain", "").trim();
        return new CodeResult(
                null,
                hostname.isEmpty()
                        ? "도메인 연결을 해제했습니다."
                        : "도메인 연결을 해제했습니다: " + hostname
        );
    }

    // ── 도메인 타입 판별 및 커맨드 생성 ────────────────────────────────────────

    private BindDomainCommand buildCommand(AgentStep step, String domain) {
        DomainType type = parseEnum(
                step.parameters().get("domainType"),
                DomainType.class,
                domain.contains(".") ? DomainType.CUSTOM_DOMAIN : DomainType.MANAGED_SUBDOMAIN
        );
        VerificationMethod method = parseEnum(
                step.parameters().get("verificationMethod"),
                VerificationMethod.class,
                null
        );
        DomainHostingTarget hostingTarget = parseEnum(
                step.parameters().get("hostingTarget"),
                DomainHostingTarget.class,
                DomainHostingTarget.GITHUB_PAGES
        );
        return type == DomainType.MANAGED_SUBDOMAIN
                ? new BindDomainCommand(type, domain, null, method, hostingTarget)
                : new BindDomainCommand(type, null, domain, method, hostingTarget);
    }

    private <T extends Enum<T>> T parseEnum(String value, Class<T> type, T defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    }

    // ── 사용자 입력 대기 ────────────────────────────────────────────────────────

    private String askUserForDomain(Long userId, String taskId) {
        String question = "연결할 도메인을 입력해주세요.\n"
                + "- 관리형 서브도메인: 라벨만 입력 (예: my-app → my-app.qeploy.com)\n"
                + "- 커스텀 도메인: 전체 주소 입력 (예: www.mysite.com)";

        return inputWaitStore.consume(taskId)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElseThrow(() -> new AgentInputRequiredException(question));
    }

    // ── 요약 생성 ──────────────────────────────────────────────────────────────

    private String buildSummary(DomainBindingResult result, Long userId, Long projectId) {
        return switch (result.type()) {
            case MANAGED_SUBDOMAIN -> String.format("""
                    관리형 서브도메인 연결 완료
                    - 도메인: %s
                    - 배포 대상: %s
                    - 상태: %s
                    - 인증서 상태: %s
                    """, result.hostname(), result.hostingTarget(), result.status(), result.certificateStatus());

            case CUSTOM_DOMAIN -> {
                VerificationGuideResult guide = fetchGuide(userId, result.domainId());
                String recordInfo = guide != null ? buildRecordInfo(guide) : "";
                yield String.format("""
                        커스텀 도메인 연결 요청 완료
                        - 도메인: %s
                        - 배포 대상: %s
                        - 상태: DNS 검증 대기 중
                        - 인증서 상태: %s
                        %s
                        DNS 설정 후 검증 요청을 보내주세요.
                        """, result.hostname(), result.hostingTarget(), result.certificateStatus(), recordInfo);
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
