package com.example.dvely.approval.presentation.dto;

import com.example.dvely.approval.domain.value.RejectOption;
import io.swagger.v3.oas.annotations.media.Schema;

/** 거절 요청 본문(선택). 보내지 않으면 이 DTO 가 생기기 전과 같다. */
@Schema(description = "거절 요청 본문 (선택)")
public record ApprovalRejectRequest(

        @Schema(
                description = "RESULT 승인에서 preview 를 어떻게 둘지. KEEP(기본) 은 preview 에 남기고, REVERT 는 preview 를 "
                        + "main 과 같은 상태로 되돌리는 커밋을 올린다. 다른 승인 유형에서는 무시된다.",
                allowableValues = {"KEEP", "REVERT"},
                nullable = true
        )
        RejectOption option
) {}
