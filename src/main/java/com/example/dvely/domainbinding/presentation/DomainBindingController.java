package com.example.dvely.domainbinding.presentation;

import com.example.dvely.domainbinding.application.command.dto.BindDomainCommand;
import com.example.dvely.domainbinding.application.facade.DomainBindingFacade;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.application.result.DomainSearchResult;
import com.example.dvely.domainbinding.application.result.VerificationGuideResult;
import com.example.dvely.domainbinding.application.service.DomainBindingSubmissionService;
import com.example.dvely.domainbinding.presentation.dto.request.BindDomainRequest;
import com.example.dvely.domainbinding.presentation.dto.response.DomainBindingSubmissionResponse;
import com.example.dvely.domainbinding.presentation.dto.response.DomainResponse;
import com.example.dvely.domainbinding.presentation.dto.response.DomainSearchResponse;
import com.example.dvely.domainbinding.presentation.dto.response.VerificationGuideResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "DomainBinding", description = "도메인 검색, 연결, DNS 검증 API")
@RestController
@RequiredArgsConstructor
public class DomainBindingController {

    private final DomainBindingFacade domainBindingFacade;
    private final DomainBindingSubmissionService submissionService;

    @Operation(summary = "도메인 검색", description = "키워드 기준으로 현재 지원하는 관리형 서브도메인의 사용 가능 여부를 조회합니다.")
    @GetMapping("/api/v1/domain-search")
    public DomainSearchResponse search(@RequestParam String keyword) {
        return toSearchResponse(domainBindingFacade.search(keyword));
    }

    @Operation(summary = "프로젝트 도메인 목록 조회", description = "프로젝트에 연결된 도메인 목록을 조회합니다.")
    @GetMapping("/api/v1/projects/{projectId}/domains")
    public List<DomainResponse> getProjectDomains(@AuthenticationPrincipal Long ownerUserId,
                                                  @PathVariable Long projectId) {
        return domainBindingFacade.getProjectDomains(ownerUserId, projectId).stream()
                .map(this::toDomainResponse)
                .toList();
    }

    @Operation(
            summary = "도메인 연결 요청",
            description = "관리형 또는 커스텀 도메인 연결을 Agent task로 제출합니다. 프로젝트 Domain Approval 정책이 켜져 있으면 승인 후 실행됩니다."
    )
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/api/v1/projects/{projectId}/domains")
    public DomainBindingSubmissionResponse bindDomain(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId,
            @Valid @RequestBody BindDomainRequest request
    ) {
        BindDomainCommand command = new BindDomainCommand(
                request.type(),
                request.label(),
                request.hostname(),
                request.verificationMethod(),
                request.hostingTarget()
        );
        var submission = submissionService.submit(ownerUserId, projectId, command);
        return new DomainBindingSubmissionResponse(
                submission.taskId(),
                submission.status().name(),
                submission.approvalIds()
        );
    }

    @Operation(summary = "도메인 상태 조회", description = "도메인 연결 및 DNS 검증 상태를 조회합니다.")
    @GetMapping("/api/v1/domains/{domainId}")
    public DomainResponse getDomain(@AuthenticationPrincipal Long ownerUserId,
                                    @PathVariable Long domainId) {
        return toDomainResponse(domainBindingFacade.getDomain(ownerUserId, domainId));
    }

    @Operation(summary = "DNS 검증 가이드 조회", description = "사용자가 DNS에 등록해야 하는 A/CNAME 레코드 값을 반환합니다.")
    @GetMapping("/api/v1/domains/{domainId}/verification-guide")
    public VerificationGuideResponse getVerificationGuide(@AuthenticationPrincipal Long ownerUserId,
                                                          @PathVariable Long domainId) {
        return toGuideResponse(domainBindingFacade.getVerificationGuide(ownerUserId, domainId));
    }

    @Operation(summary = "DNS 검증 재시도", description = "현재 DNS 설정을 조회해 도메인 연결 상태를 갱신합니다.")
    @PostMapping("/api/v1/domains/{domainId}/verification-checks")
    public DomainResponse checkVerification(@AuthenticationPrincipal Long ownerUserId,
                                            @PathVariable Long domainId) {
        return toDomainResponse(domainBindingFacade.checkVerification(ownerUserId, domainId));
    }

    @Operation(
            summary = "도메인 연결 해제 요청",
            description = "도메인 연결 해제를 Agent task로 제출합니다. 프로젝트 Domain Approval 정책이 켜져 있으면 승인 후 제거합니다."
    )
    @ResponseStatus(HttpStatus.ACCEPTED)
    @DeleteMapping("/api/v1/domains/{domainId}")
    public DomainBindingSubmissionResponse deleteDomain(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long domainId
    ) {
        var submission = submissionService.submitDelete(ownerUserId, domainId);
        return new DomainBindingSubmissionResponse(
                submission.taskId(),
                submission.status().name(),
                submission.approvalIds()
        );
    }

    private DomainSearchResponse toSearchResponse(DomainSearchResult result) {
        return new DomainSearchResponse(
                result.keyword(),
                result.results().stream()
                        .map(candidate -> new DomainSearchResponse.Result(
                                candidate.type().getValue(),
                                candidate.hostname(),
                                candidate.available(),
                                candidate.price(),
                                candidate.currency()
                        ))
                        .toList()
        );
    }

    private DomainResponse toDomainResponse(DomainBindingResult result) {
        return new DomainResponse(
                result.domainId(),
                result.projectId(),
                result.type().getValue(),
                result.hostingTarget().name(),
                result.hostname(),
                result.status().name(),
                result.verificationMethod() == null ? null : result.verificationMethod().name(),
                result.dnsTarget(),
                result.httpsEnforced(),
                result.certificateStatus().name(),
                result.certificateExpiresAt(),
                result.lastCheckedAt(),
                result.createdAt(),
                result.updatedAt()
        );
    }

    private VerificationGuideResponse toGuideResponse(VerificationGuideResult result) {
        return new VerificationGuideResponse(
                result.hostname(),
                result.verificationMethod().name(),
                result.records().stream()
                        .map(record -> new VerificationGuideResponse.Record(
                                record.type(),
                                record.host(),
                                record.value()
                        ))
                        .toList()
        );
    }
}
