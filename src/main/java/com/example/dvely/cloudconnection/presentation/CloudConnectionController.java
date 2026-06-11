package com.example.dvely.cloudconnection.presentation;

import com.example.dvely.cloudconnection.application.command.dto.CreateCloudConnectionCommand;
import com.example.dvely.cloudconnection.application.facade.CloudConnectionFacade;
import com.example.dvely.cloudconnection.application.result.CloudConnectionHealthResult;
import com.example.dvely.cloudconnection.application.result.CloudConnectionResult;
import com.example.dvely.cloudconnection.application.result.CloudConnectionVerificationJobResult;
import com.example.dvely.cloudconnection.application.result.CreateCloudConnectionResult;
import com.example.dvely.cloudconnection.presentation.dto.request.CreateCloudConnectionRequest;
import com.example.dvely.cloudconnection.presentation.dto.response.CloudConnectionHealthResponse;
import com.example.dvely.cloudconnection.presentation.dto.response.CloudConnectionResponse;
import com.example.dvely.cloudconnection.presentation.dto.response.CloudConnectionVerificationJobResponse;
import com.example.dvely.cloudconnection.presentation.dto.response.CreateCloudConnectionResponse;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "CloudConnection", description = "AWS/GCP BYOC 클라우드 연결 API")
@RestController
@RequiredArgsConstructor
public class CloudConnectionController {

    private final CloudConnectionFacade cloudConnectionFacade;

    @Operation(summary = "클라우드 연결 목록 조회", description = "현재 사용자가 등록한 AWS/GCP 연결 목록을 조회합니다.")
    @GetMapping("/api/v1/cloud-connections")
    public List<CloudConnectionResponse> getCloudConnections(@AuthenticationPrincipal Long ownerUserId) {
        return cloudConnectionFacade.getCloudConnections(ownerUserId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Operation(summary = "클라우드 연결 등록", description = "AWS 또는 GCP 연결 정보를 등록하고 health check 추적 ID를 반환합니다.")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/v1/cloud-connections")
    public CreateCloudConnectionResponse create(@AuthenticationPrincipal Long ownerUserId,
                                                @Valid @RequestBody CreateCloudConnectionRequest request) {
        CreateCloudConnectionCommand command = new CreateCloudConnectionCommand(
                request.provider(),
                request.displayName(),
                request.accountId(),
                request.region(),
                request.roleArn(),
                request.awsCredentialType(),
                request.accessKeyId(),
                request.secretAccessKey(),
                request.sessionToken(),
                request.gcpCredentialType(),
                request.serviceAccountKeyJson(),
                request.projectId(),
                request.serviceAccountEmail()
        );
        return toCreateResponse(cloudConnectionFacade.create(ownerUserId, command));
    }

    @Operation(summary = "클라우드 연결 상세 조회", description = "특정 AWS/GCP 연결 상세 정보를 조회합니다.")
    @GetMapping("/api/v1/cloud-connections/{cloudConnectionId}")
    public CloudConnectionResponse getCloudConnection(@AuthenticationPrincipal Long ownerUserId,
                                                      @PathVariable Long cloudConnectionId) {
        return toResponse(cloudConnectionFacade.getCloudConnection(ownerUserId, cloudConnectionId));
    }

    @Operation(summary = "클라우드 연결 health 조회", description = "저장된 형식 검증/실제 권한 확인 상태를 조회합니다.")
    @GetMapping("/api/v1/cloud-connections/{cloudConnectionId}/health")
    public CloudConnectionHealthResponse checkHealth(@AuthenticationPrincipal Long ownerUserId,
                                                     @PathVariable Long cloudConnectionId) {
        return toHealthResponse(cloudConnectionFacade.checkHealth(ownerUserId, cloudConnectionId));
    }

    @Operation(summary = "클라우드 연결 재검증 요청", description = "실제 AWS/GCP 권한 확인 Job을 생성합니다.")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/api/v1/cloud-connections/{cloudConnectionId}/verification-jobs")
    public CloudConnectionVerificationJobResponse requestVerification(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long cloudConnectionId
    ) {
        return toJobResponse(cloudConnectionFacade.requestVerification(ownerUserId, cloudConnectionId));
    }

    @Operation(summary = "클라우드 연결 검증 Job 조회")
    @GetMapping("/api/v1/cloud-connection-verification-jobs/{jobId}")
    public CloudConnectionVerificationJobResponse getVerificationJob(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable String jobId
    ) {
        return toJobResponse(cloudConnectionFacade.getVerificationJob(ownerUserId, jobId));
    }

    @Operation(summary = "클라우드 연결 해제", description = "Qeploy에 저장된 외부 클라우드 연결 정보를 삭제합니다.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/api/v1/cloud-connections/{cloudConnectionId}")
    public void delete(@AuthenticationPrincipal Long ownerUserId,
                       @PathVariable Long cloudConnectionId) {
        cloudConnectionFacade.delete(ownerUserId, cloudConnectionId);
    }

    private CloudConnectionResponse toResponse(CloudConnectionResult result) {
        return new CloudConnectionResponse(
                result.cloudConnectionId(),
                result.provider().name(),
                result.displayName(),
                result.accountId(),
                result.region(),
                result.roleArn(),
                result.awsCredentialType(),
                maskAccessKeyId(result.accessKeyId()),
                result.secretAccessKeyConfigured(),
                result.sessionTokenConfigured(),
                result.gcpCredentialType(),
                result.serviceAccountKeyConfigured(),
                result.gcpProjectId(),
                result.serviceAccountEmail(),
                result.status().name(),
                result.lastCheckedAt(),
                result.createdAt(),
                result.updatedAt()
        );
    }

    private String maskAccessKeyId(String accessKeyId) {
        if (accessKeyId == null || accessKeyId.isBlank()) {
            return null;
        }
        if (accessKeyId.length() <= 8) {
            return "****";
        }
        return accessKeyId.substring(0, 4) + "************" + accessKeyId.substring(accessKeyId.length() - 4);
    }

    private CreateCloudConnectionResponse toCreateResponse(CreateCloudConnectionResult result) {
        return new CreateCloudConnectionResponse(
                result.cloudConnectionId(),
                result.provider().name(),
                result.status().name(),
                result.jobId()
        );
    }

    private CloudConnectionHealthResponse toHealthResponse(CloudConnectionHealthResult result) {
        return new CloudConnectionHealthResponse(
                result.cloudConnectionId(),
                result.provider().name(),
                result.status().name(),
                result.message(),
                result.checkedAt()
        );
    }

    private CloudConnectionVerificationJobResponse toJobResponse(CloudConnectionVerificationJobResult result) {
        return new CloudConnectionVerificationJobResponse(
                result.jobId(),
                result.cloudConnectionId(),
                result.status().name(),
                result.connectionStatus().name(),
                result.message(),
                result.attempt(),
                result.createdAt(),
                result.startedAt(),
                result.completedAt()
        );
    }
}
