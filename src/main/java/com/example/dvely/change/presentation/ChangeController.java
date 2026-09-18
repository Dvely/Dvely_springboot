package com.example.dvely.change.presentation;

import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.change.presentation.dto.ChangeDiffResponse;
import com.example.dvely.change.presentation.dto.ChangeResponse;
import com.example.dvely.common.paging.CursorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Change", description = "Agent CODE 작업 결과(코드 변경) 조회 API. 변경 상태와 diff를 제공합니다.")
@RestController
@RequiredArgsConstructor
public class ChangeController {

    private final ChangeService changeService;

    @Operation(
            summary = "프로젝트 Change 목록 조회",
            description = "프로젝트에서 실행된 Agent CODE 작업(코드 변경) 이력을 최신순으로 조회합니다. "
                          + "limit 를 주지 않으면 최신 200건까지 내려가고, 더 있으면 응답 헤더 "
                          + "X-Qeploy-Next-Cursor 에 다음 커서가 실립니다(after 로 넘겨 이어 받습니다)."
    )
    @GetMapping("/api/v1/projects/{projectId}/changes")
    public ResponseEntity<List<ChangeResponse>> getProjectChanges(
            @AuthenticationPrincipal Long ownerUserId,
            @PathVariable Long projectId,
            @Parameter(description = "한 번에 받을 최대 건수(1~500). 없으면 200") @RequestParam(required = false) Integer limit,
            @Parameter(description = "이전 페이지의 X-Qeploy-Next-Cursor 값. 그 항목보다 오래된 건만 반환")
            @RequestParam(required = false) String after
    ) {
        return CursorResponse.of(
                changeService.getProjectChanges(ownerUserId, projectId, limit, after)
                        .map(ChangeResponse::from));
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
