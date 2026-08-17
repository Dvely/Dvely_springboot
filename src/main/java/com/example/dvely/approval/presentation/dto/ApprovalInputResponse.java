package com.example.dvely.approval.presentation.dto;

import com.example.dvely.approval.application.result.ApprovalInput;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "승인할 때 함께 받아야 하는 입력의 스펙. 이 값이 null 이면 단순 승인/거절입니다.")
public record ApprovalInputResponse(
        @Schema(description = "approve 요청 본문의 키", example = "repositoryName")
        String field,

        @Schema(description = "입력창에 미리 채워둘 값. 비운 채 승인하면 서버도 이 값을 씁니다.", example = "my-project")
        String defaultValue,

        @Schema(description = "true면 값이 반드시 있어야 합니다. false면 비워도 defaultValue로 진행합니다.", example = "false")
        boolean required,

        @Schema(description = "값이 만족해야 하는 정규식. 서버도 같은 규칙으로 정규화합니다.", example = "^[a-z0-9-]+$")
        String pattern,

        @Schema(description = "최대 길이", example = "100")
        Integer maxLength
) {
    public static ApprovalInputResponse from(ApprovalInput input) {
        return input == null ? null : new ApprovalInputResponse(
                input.field(),
                input.defaultValue(),
                input.required(),
                input.pattern(),
                input.maxLength()
        );
    }
}
