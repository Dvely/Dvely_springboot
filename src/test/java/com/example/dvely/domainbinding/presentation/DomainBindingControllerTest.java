package com.example.dvely.domainbinding.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.dto.AgentSubmission;
import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.domainbinding.application.command.dto.BindDomainCommand;
import com.example.dvely.domainbinding.application.facade.DomainBindingFacade;
import com.example.dvely.domainbinding.application.service.DomainBindingSubmissionService;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainType;
import com.example.dvely.domainbinding.presentation.dto.request.BindDomainRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DomainBindingControllerTest {

    @Mock
    private DomainBindingFacade domainBindingFacade;

    @Mock
    private DomainBindingSubmissionService submissionService;

    @Test
    void bindDomainReturnsApprovalAwareTaskSubmission() {
        DomainBindingController controller =
                new DomainBindingController(domainBindingFacade, submissionService);
        when(submissionService.submit(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(11L),
                any(BindDomainCommand.class)
        )).thenReturn(new AgentSubmission("task-1", TaskStatus.WAITING_APPROVAL, List.of(31L)));

        var response = controller.bindDomain(
                1L,
                11L,
                new BindDomainRequest(
                        DomainType.MANAGED_SUBDOMAIN,
                        "sample",
                        null,
                        null,
                        DomainHostingTarget.GITHUB_PAGES
                )
        );

        assertThat(response.taskId()).isEqualTo("task-1");
        assertThat(response.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(response.approvalIds()).containsExactly(31L);

        ArgumentCaptor<BindDomainCommand> commandCaptor = ArgumentCaptor.forClass(BindDomainCommand.class);
        verify(submissionService).submit(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(11L),
                commandCaptor.capture()
        );
        assertThat(commandCaptor.getValue().label()).isEqualTo("sample");
        assertThat(commandCaptor.getValue().hostingTarget()).isEqualTo(DomainHostingTarget.GITHUB_PAGES);
    }

    @Test
    void deleteDomainReturnsApprovalAwareTaskSubmission() {
        DomainBindingController controller =
                new DomainBindingController(domainBindingFacade, submissionService);
        when(submissionService.submitDelete(1L, 31L))
                .thenReturn(new AgentSubmission("task-delete", TaskStatus.WAITING_APPROVAL, List.of(41L)));

        var response = controller.deleteDomain(1L, 31L);

        assertThat(response.taskId()).isEqualTo("task-delete");
        assertThat(response.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(response.approvalIds()).containsExactly(41L);
        verify(submissionService).submitDelete(1L, 31L);
    }
}
