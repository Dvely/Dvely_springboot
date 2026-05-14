package com.example.dvely.domainbinding.presentation;

import com.example.dvely.domainbinding.application.command.dto.BindDomainCommand;
import com.example.dvely.domainbinding.application.facade.DomainBindingFacade;
import com.example.dvely.domainbinding.application.result.DomainBindingResult;
import com.example.dvely.domainbinding.application.result.DomainSearchResult;
import com.example.dvely.domainbinding.application.result.VerificationGuideResult;
import com.example.dvely.domainbinding.presentation.dto.request.BindDomainRequest;
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

    @Operation(summary = "도메인 검색", description = "키워드 기준으로 관리형 서브도메인과 구매형 도메인 후보를 조회합니다.")
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

    @Operation(summary = "도메인 연결", description = "관리형 서브도메인 또는 사용자가 보유한 커스텀 도메인을 프로젝트에 연결합니다.")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/v1/projects/{projectId}/domains")
    public DomainResponse bindDomain(@AuthenticationPrincipal Long ownerUserId,
                                     @PathVariable Long projectId,
                                     @Valid @RequestBody BindDomainRequest request) {
        BindDomainCommand command = new BindDomainCommand(
                request.type(),
                request.label(),
                request.hostname(),
                request.verificationMethod()
        );
        return toDomainResponse(domainBindingFacade.bindDomain(ownerUserId, projectId, command));
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

    @Operation(summary = "도메인 연결 해제", description = "도메인 연결을 제거합니다. 관리형 서브도메인은 Cloudflare DNS 레코드도 삭제합니다.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/api/v1/domains/{domainId}")
    public void deleteDomain(@AuthenticationPrincipal Long ownerUserId,
                             @PathVariable Long domainId) {
        domainBindingFacade.deleteDomain(ownerUserId, domainId);
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
                result.hostname(),
                result.status().name(),
                result.verificationMethod() == null ? null : result.verificationMethod().name(),
                result.dnsTarget(),
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
