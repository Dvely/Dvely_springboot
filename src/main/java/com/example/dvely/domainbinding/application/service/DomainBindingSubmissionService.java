package com.example.dvely.domainbinding.application.service;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.domain.value.AiProvider;
import com.example.dvely.domainbinding.application.command.dto.BindDomainCommand;
import com.example.dvely.domainbinding.application.query.DomainBindingQueryService;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.domain.value.DomainType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DomainBindingSubmissionService {

    private final AgentOrchestrator agentOrchestrator;
    private final DomainHostingAdapterRegistry hostingAdapterRegistry;
    private final DomainBindingQueryService queryService;

    public AgentSubmission submit(Long ownerUserId, Long projectId, BindDomainCommand command) {
        if (command.type() == null) {
            throw new IllegalArgumentException("도메인 유형이 필요합니다.");
        }
        if (command.type() == DomainType.PURCHASABLE_DOMAIN) {
            throw new IllegalArgumentException("구매형 도메인은 현재 지원되지 않습니다.");
        }
        hostingAdapterRegistry.resolve(command.hostingTarget());

        String domain = command.type() == DomainType.MANAGED_SUBDOMAIN
                ? command.label()
                : command.hostname();
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException(
                    command.type() == DomainType.MANAGED_SUBDOMAIN
                            ? "관리형 서브도메인 label이 필요합니다."
                            : "커스텀 도메인 hostname이 필요합니다."
            );
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("domain", domain.trim());
        parameters.put("domainType", command.type().name());
        parameters.put("hostingTarget", command.hostingTarget().name());
        parameters.put("instruction", buildApprovalSummary(command, domain));
        if (command.verificationMethod() != null) {
            parameters.put("verificationMethod", command.verificationMethod().name());
        }

        return submitPlan(ownerUserId, projectId, parameters);
    }

    public AgentSubmission submitDelete(Long ownerUserId, Long domainId) {
        DomainBindingResult domain = queryService.getDomain(ownerUserId, domainId);
        hostingAdapterRegistry.resolve(domain.hostingTarget());

        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("operation", "DELETE");
        parameters.put("domainId", domainId.toString());
        parameters.put("domain", domain.hostname());
        parameters.put("domainType", domain.type().name());
        parameters.put("hostingTarget", domain.hostingTarget().name());
        parameters.put(
                "instruction",
                "도메인 연결 해제: " + domain.hostname() + " / 대상: " + domain.hostingTarget().name()
        );
        return submitPlan(ownerUserId, domain.projectId(), parameters);
    }

    private String buildApprovalSummary(BindDomainCommand command, String domain) {
        return "도메인 연결: "
                + domain
                + " / 대상: "
                + command.hostingTarget().name()
                + " / HTTPS 강제 적용";
    }

    private AgentSubmission submitPlan(Long ownerUserId,
                                       Long projectId,
                                       Map<String, String> parameters) {
        AgentPlan plan = new AgentPlan(
                List.of(new AgentStep(AgentType.DOMAIN_BIND, Map.copyOf(parameters))),
                "직접 Domain API 요청을 승인 정책이 적용되는 Agent task로 실행합니다.",
                AiProvider.ANTHROPIC,
                projectId
        );
        return agentOrchestrator.submit(plan, ownerUserId, null);
    }
}
