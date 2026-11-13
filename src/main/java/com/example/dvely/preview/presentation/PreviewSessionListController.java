package com.example.dvely.preview.presentation;

import com.example.dvely.preview.application.service.PreviewSessionListService;
import com.example.dvely.preview.presentation.dto.response.PreviewSessionListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 살아 있는 프리뷰 목록(#651). 세션 하나를 다루는 {@link PreviewSessionController} 와 나눠 둔다. */
@Tag(name = "Preview", description = "Agent CODE 작업이 띄운 Docker 프리뷰 컨테이너에 대한 리버스 프록시 및 운영(상태/로그) 조회 API.")
@RestController
@RequiredArgsConstructor
public class PreviewSessionListController {

    private final PreviewSessionListService listService;

    @Operation(
            summary = "내 프리뷰 목록",
            description = "내 살아 있는 프리뷰(ACTIVE·PROVISIONING)를 오래 안 본 순으로 돌려줍니다(상한 회수가 고르는 순서와 같음). "
                    + "프리뷰 상한(`PREVIEW_LIMIT_EXCEEDED`)에 걸렸을 때 무엇을 닫을지 고르는 데 씁니다. "
                    + "`maxLivePreviews` 는 사용자 상한입니다. 항목마다 `closable` 과, 닫을 수 없으면 `closeBlockedReason`"
                    + "(작업 상태 이름 — 예: `WAITING_RESULT_APPROVAL` — 또는 준비 중이면 `PROVISIONING`)을 줍니다. "
                    + "`DELETE /preview-sessions/{id}` 와 같은 규칙입니다(#650). `taskStatus` 가 `FAILED` 면 닫을 수는 있지만 "
                    + "그 작업의 재시도가 처음부터 시작됩니다. `taskId` 가 null 이면 작업 없이 띄운 프로젝트 프리뷰입니다. "
                    + "열 주소는 싣지 않습니다(`POST /preview-sessions/{id}/access` 로 받습니다). Docker 를 조회하지 않으므로 "
                    + "컨테이너가 막 사라진 세션이 잠깐 보일 수 있고, 닫아도 해가 없습니다. 개인 액세스 토큰(READ)으로도 부를 수 있습니다."
    )
    @GetMapping("/api/v1/preview-sessions")
    public PreviewSessionListResponse list(@AuthenticationPrincipal Long ownerUserId) {
        return PreviewSessionListResponse.from(listService.list(ownerUserId));
    }
}
