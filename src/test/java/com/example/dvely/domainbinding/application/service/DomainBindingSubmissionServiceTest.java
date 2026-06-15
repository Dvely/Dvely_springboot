package com.example.dvely.domainbinding.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentPlan;
import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.agent.application.orchestrator.AgentOrchestrator;
import com.example.dvely.domainbinding.application.command.dto.BindDomainCommand;
import com.example.dvely.domainbinding.application.port.out.DomainHostingAdapter;
import com.example.dvely.domainbinding.application.query.DomainBindingQueryService;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DomainBindingSubmissionServiceTest {

    @Mock
    private AgentOrchestrator agentOrchestrator;

    @Mock
    private DomainHostingAdapterRegistry hostingAdapterRegistry;

    @Mock
    private DomainHostingAdapter hostingAdapter;

    @Mock
    private DomainBindingQueryService queryService;

    private DomainBindingSubmissionService service;

    @BeforeEach
    void setUp() {
        service = new DomainBindingSubmissionService(
                agentOrchestrator,
                hostingAdapterRegistry,
                queryService
        );
    }

    @Test
    void submitsDomainRequestThroughApprovalAwareAgentTask() {
        when(hostingAdapterRegistry.resolve(DomainHostingTarget.GITHUB_PAGES))
                .thenReturn(hostingAdapter);
        when(agentOrchestrator.submit(any(AgentPlan.class), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new AgentSubmission("task-1", TaskStatus.WAITING_APPROVAL, List.of(31L)));

        var submission = service.submit(
                1L,
                11L,
                new BindDomainCommand(
                        DomainType.CUSTOM_DOMAIN,
                        null,
                        "www.example.com",
                        null,
                        DomainHostingTarget.GITHUB_PAGES
                )
        );

        assertThat(submission.status()).isEqualTo(TaskStatus.WAITING_APPROVAL);
        assertThat(submission.approvalIds()).containsExactly(31L);

        ArgumentCaptor<AgentPlan> planCaptor = ArgumentCaptor.forClass(AgentPlan.class);
        verify(agentOrchestrator).submit(planCaptor.capture(), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.isNull());
        AgentPlan plan = planCaptor.getValue();
        assertThat(plan.projectId()).isEqualTo(11L);
        assertThat(plan.steps()).singleElement().satisfies(step -> {
            assertThat(step.agentType().name()).isEqualTo("DOMAIN_BIND");
            assertThat(step.parameters().get("domain")).isEqualTo("www.example.com");
            assertThat(step.parameters().get("hostingTarget")).isEqualTo("GITHUB_PAGES");
            assertThat(step.parameters().get("instruction")).contains("HTTPS 강제 적용");
        });
    }

    @Test
    void rejectsUnsupportedPurchasableDomainBeforeCreatingTask() {
        assertThatThrownBy(() -> service.submit(
                1L,
                11L,
                new BindDomainCommand(
                        DomainType.PURCHASABLE_DOMAIN,
                        null,
                        "sample.app",
                        null,
                        DomainHostingTarget.GITHUB_PAGES
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원되지 않습니다");

        verify(agentOrchestrator, never()).submit(any(), any(), any());
    }

    @Test
    void rejectsBlankManagedLabelBeforeCreatingTask() {
        when(hostingAdapterRegistry.resolve(DomainHostingTarget.GITHUB_PAGES))
                .thenReturn(hostingAdapter);

        assertThatThrownBy(() -> service.submit(
                1L,
                11L,
                new BindDomainCommand(
                        DomainType.MANAGED_SUBDOMAIN,
                        " ",
                        null,
                        null,
                        DomainHostingTarget.GITHUB_PAGES
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("label");

        verify(agentOrchestrator, never()).submit(any(), any(), any());
    }

    @Test
    void rejectsDeploymentTargetWithoutRegisteredAdapter() {
        when(hostingAdapterRegistry.resolve(DomainHostingTarget.AWS))
                .thenThrow(new IllegalArgumentException("AWS 배포 대상의 도메인 연결은 아직 지원되지 않습니다."));

        assertThatThrownBy(() -> service.submit(
                1L,
                11L,
                new BindDomainCommand(
                        DomainType.CUSTOM_DOMAIN,
                        null,
                        "www.example.com",
                        null,
                        DomainHostingTarget.AWS
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AWS", "지원되지 않습니다");

        verify(agentOrchestrator, never()).submit(any(), any(), any());
    }

    @Test
    void submitsDomainDeleteThroughSameApprovalPolicy() {
        when(queryService.getDomain(1L, 31L)).thenReturn(domainResult());
        when(hostingAdapterRegistry.resolve(DomainHostingTarget.GITHUB_PAGES))
                .thenReturn(hostingAdapter);
        when(agentOrchestrator.submit(any(AgentPlan.class), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new AgentSubmission("task-delete", TaskStatus.WAITING_APPROVAL, List.of(41L)));

        var submission = service.submitDelete(1L, 31L);

        assertThat(submission.approvalIds()).containsExactly(41L);
        ArgumentCaptor<AgentPlan> planCaptor = ArgumentCaptor.forClass(AgentPlan.class);
        verify(agentOrchestrator).submit(planCaptor.capture(), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.isNull());
        assertThat(planCaptor.getValue().steps()).singleElement().satisfies(step -> {
            assertThat(step.parameters().get("operation")).isEqualTo("DELETE");
            assertThat(step.parameters().get("domainId")).isEqualTo("31");
            assertThat(step.parameters().get("instruction")).contains("연결 해제");
        });
    }

    private DomainBindingResult domainResult() {
        LocalDateTime now = LocalDateTime.now();
        return new DomainBindingResult(
                31L,
                11L,
                DomainType.CUSTOM_DOMAIN,
                DomainHostingTarget.GITHUB_PAGES,
                "www.example.com",
                DomainStatus.CONNECTED,
                VerificationMethod.CNAME,
                "octo.github.io",
                true,
                CertificateStatus.ACTIVE,
                null,
                now,
                now,
                now
        );
    }
}
