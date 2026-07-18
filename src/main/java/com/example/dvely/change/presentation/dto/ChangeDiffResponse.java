package com.example.dvely.change.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "코드 변경의 git diff")
public record ChangeDiffResponse(
        @Schema(description = "Change ID") Long changeId,
        @Schema(description = "unified diff 형식의 변경 내용 텍스트") String diff
) {
}
