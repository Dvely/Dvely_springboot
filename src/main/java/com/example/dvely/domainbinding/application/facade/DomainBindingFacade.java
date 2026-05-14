package com.example.dvely.domainbinding.application.facade;

import com.example.dvely.domainbinding.application.command.DomainBindingCommandService;
import com.example.dvely.domainbinding.application.command.dto.BindDomainCommand;
import com.example.dvely.domainbinding.application.query.DomainBindingQueryService;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.application.result.DomainSearchResult;
import com.example.dvely.domainbinding.application.result.VerificationGuideResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DomainBindingFacade {

    private final DomainBindingQueryService queryService;
    private final DomainBindingCommandService commandService;

    public DomainSearchResult search(String keyword) {
        return queryService.search(keyword);
    }

    public List<DomainBindingResult> getProjectDomains(Long ownerUserId, Long projectId) {
        return queryService.getProjectDomains(ownerUserId, projectId);
    }

    public DomainBindingResult bindDomain(Long ownerUserId, Long projectId, BindDomainCommand command) {
        return commandService.bindDomain(ownerUserId, projectId, command);
    }

    public DomainBindingResult getDomain(Long ownerUserId, Long domainId) {
        return queryService.getDomain(ownerUserId, domainId);
    }

    public VerificationGuideResult getVerificationGuide(Long ownerUserId, Long domainId) {
        return queryService.getVerificationGuide(ownerUserId, domainId);
    }

    public DomainBindingResult checkVerification(Long ownerUserId, Long domainId) {
        return commandService.checkVerification(ownerUserId, domainId);
    }

    public void deleteDomain(Long ownerUserId, Long domainId) {
        commandService.deleteDomain(ownerUserId, domainId);
    }
}
