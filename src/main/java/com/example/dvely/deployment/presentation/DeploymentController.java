package com.example.dvely.deployment.presentation;

import com.example.dvely.deployment.application.facade.DeploymentFacade;
import com.example.dvely.deployment.application.result.VersionDetailResult;
import com.example.dvely.deployment.application.result.VersionResult;
import com.example.dvely.deployment.presentation.dto.response.VersionDetailResponse;
import com.example.dvely.deployment.presentation.dto.response.VersionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Deployment", description = "배포 버전 관리 API. 프로젝트의 merge 기반 버전 목록 조회 및 배포 이력을 제공합니다.")
@RestController
@RequiredArgsConstructor
public class DeploymentController {

    // TODO: auth 모듈 인증 컨텍스트 도입 후 헤더 기반 식별 교체
    private static final String USER_ID_HEADER = "X-USER-ID";

    private final DeploymentFacade deploymentFacade;

    @Operation(
            summary = "프로젝트 버전 목록 조회",
            description = "프로젝트에 연결된 저장소의 merge 커밋을 기준으로 생성된 버전 목록을 반환합니다. " +
                          "배포 히스토리 화면에서 버전별 배포 상태와 커밋 정보를 보여줄 때 사용합니다."
    )
    @GetMapping("/api/v1/projects/{projectId}/versions")
    public List<VersionResponse> getVersions(
            @Parameter(hidden = true) @RequestHeader(USER_ID_HEADER) Long ownerUserId,
            @Parameter(description = "버전 목록을 조회할 프로젝트 ID") @PathVariable Long projectId
    ) {
        return deploymentFacade.getVersions(ownerUserId, projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Operation(
            summary = "버전 상세 조회",
            description = "특정 버전의 상세 정보를 반환합니다. " +
                          "버전 목록에서 항목을 선택했을 때 merge 유저, PR 번호, 배포 URL 등 " +
                          "상세 내용을 보여주는 화면에서 사용합니다."
    )
    @GetMapping("/api/v1/versions/{versionId}")
    public VersionDetailResponse getVersionDetail(
            @Parameter(hidden = true) @RequestHeader(USER_ID_HEADER) Long ownerUserId,
            @Parameter(description = "조회할 버전 ID") @PathVariable Long versionId
    ) {
        return toDetailResponse(deploymentFacade.getVersionDetail(ownerUserId, versionId));
    }

    private VersionResponse toResponse(VersionResult result) {
        return new VersionResponse(
                result.versionId(),
                result.versionName(),
                result.commitSha(),
                result.title(),
                result.deployStatus(),
                result.mergedAt()
        );
    }

    private VersionDetailResponse toDetailResponse(VersionDetailResult result) {
        return new VersionDetailResponse(
                result.versionId(),
                result.versionName(),
                result.commitSha(),
                result.title(),
                result.description(),
                result.deployStatus(),
                result.deployedUrl(),
                result.mergedBy(),
                result.mergedByAvatarUrl(),
                result.prNumber(),
                result.mergedAt()
        );
    }
}
