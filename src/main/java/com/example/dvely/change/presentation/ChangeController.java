package com.example.dvely.change.presentation;

import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.change.presentation.dto.ChangeDiffResponse;
import com.example.dvely.change.presentation.dto.ChangeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Change", description = "Agent CODE 작업 결과(코드 변경) 조회 API. 변경 상태와 diff를 제공합니다.")
@RestController
@RequiredArgsConstructor
public class ChangeController {

    private final ChangeService changeService;

    @Operation(
            summary = "프로젝트 Change 목록 조회",
            description = "프로젝트에서 실행된 모든 Agent CODE 작업(코드 변경) 이력을 조회합니다."
    )
    @GetMapping("/api/v1/projects/{projectId}/changes")
    public List<ChangeResponse> getProjectChanges(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId
    ) {
        return changeService.getProjectChanges(ownerUserId, projectId).stream()
                .map(ChangeResponse::from)
                .toList();
    }

    @Operation(
            summary = "Change 상세 조회",
            description = "코드 변경 한 건의 상태·요약·연결된 taskId/previewSessionId를 조회합니다."
    )
    @GetMapping("/api/v1/changes/{changeId}")
    public ChangeResponse getChange(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long changeId
    ) {
        return ChangeResponse.from(changeService.getChange(ownerUserId, changeId));
    }

    @Operation(
            summary = "Change diff 조회",
            description = "코드 변경의 git diff 텍스트를 조회합니다."
    )
    @GetMapping("/api/v1/changes/{changeId}/diff")
    public ChangeDiffResponse getDiff(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long changeId
    ) {
        return new ChangeDiffResponse(changeId, changeService.getDiff(ownerUserId, changeId));
    }
}
