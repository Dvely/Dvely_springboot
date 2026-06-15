package com.example.dvely.change.presentation;

import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.change.presentation.dto.ChangeDiffResponse;
import com.example.dvely.change.presentation.dto.ChangeResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChangeController {

    private final ChangeService changeService;

    @Operation(summary = "프로젝트 Change 목록 조회")
    @GetMapping("/api/v1/projects/{projectId}/changes")
    public List<ChangeResponse> getProjectChanges(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId
    ) {
        return changeService.getProjectChanges(ownerUserId, projectId).stream()
                .map(ChangeResponse::from)
                .toList();
    }

    @Operation(summary = "Change 상세 조회")
    @GetMapping("/api/v1/changes/{changeId}")
    public ChangeResponse getChange(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long changeId
    ) {
        return ChangeResponse.from(changeService.getChange(ownerUserId, changeId));
    }

    @Operation(summary = "Change diff 조회")
    @GetMapping("/api/v1/changes/{changeId}/diff")
    public ChangeDiffResponse getDiff(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long changeId
    ) {
        return new ChangeDiffResponse(changeId, changeService.getDiff(ownerUserId, changeId));
    }
}
