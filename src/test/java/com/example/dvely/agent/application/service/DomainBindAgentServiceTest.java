package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.domain.value.AgentType;
import com.example.dvely.agent.infrastructure.store.InputWaitStore;
import com.example.dvely.domainbinding.application.command.dto.BindDomainCommand;
import com.example.dvely.domainbinding.application.facade.DomainBindingFacade;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.domain.value.VerificationMethod;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DomainBindAgentServiceTest {

    @Mock
    private DomainBindingFacade domainBindingFacade;

    @Mock
    private InputWaitStore inputWaitStore;

    @Test
    void executesApprovedRequestWithSubmittedHostingTargetAndVerificationMethod() {
        DomainBindAgentService service = new DomainBindAgentService(domainBindingFacade, inputWaitStore);
        AgentStep step = new AgentStep(AgentType.DOMAIN_BIND, Map.of(
                "domain", "www.example.com",
                "domainType", "CUSTOM_DOMAIN",
                "hostingTarget", "GITHUB_PAGES",
                "verificationMethod", "A"
        ));
        when(domainBindingFacade.bindDomain(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(11L),
                any(BindDomainCommand.class)
        )).thenReturn(result());

        var execution = service.execute(step, 1L, "task-1", 11L);

        ArgumentCaptor<BindDomainCommand> commandCaptor = ArgumentCaptor.forClass(BindDomainCommand.class);
        verify(domainBindingFacade).bindDomain(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(11L),
                commandCaptor.capture()
        );
        assertThat(commandCaptor.getValue().type()).isEqualTo(DomainType.CUSTOM_DOMAIN);
        assertThat(commandCaptor.getValue().hostingTarget()).isEqualTo(DomainHostingTarget.GITHUB_PAGES);
        assertThat(commandCaptor.getValue().verificationMethod()).isEqualTo(VerificationMethod.A);
        assertThat(execution.summary()).contains("GITHUB_PAGES", "PENDING");
    }

    @Test
    void executesApprovedDomainDeleteRequest() {
        DomainBindAgentService service = new DomainBindAgentService(domainBindingFacade, inputWaitStore);
        AgentStep step = new AgentStep(AgentType.DOMAIN_BIND, Map.of(
                "operation", "DELETE",
                "domainId", "31",
                "domain", "www.example.com"
        ));

        var execution = service.execute(step, 1L, "task-delete", 11L);

        verify(domainBindingFacade).deleteDomain(1L, 31L);
        assertThat(execution.summary()).contains("www.example.com", "해제");
    }

    private DomainBindingResult result() {
        LocalDateTime now = LocalDateTime.now();
        return new DomainBindingResult(
                31L,
                11L,
                DomainType.CUSTOM_DOMAIN,
                DomainHostingTarget.GITHUB_PAGES,
                "www.example.com",
                DomainStatus.VERIFYING,
                VerificationMethod.A,
                "octo.github.io",
                false,
                CertificateStatus.PENDING,
                null,
                null,
                now,
                now
        );
    }
}
