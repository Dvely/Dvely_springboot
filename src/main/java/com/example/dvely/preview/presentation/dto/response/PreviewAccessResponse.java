package com.example.dvely.preview.presentation.dto.response;

import com.example.dvely.preview.application.result.PreviewAccessGrant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "프리뷰 열람 권한 발급 결과")
public record PreviewAccessResponse(

        @Schema(description = "Preview 세션 ID")
        String sessionId,

        @Schema(description = "이 호출로 새로 발급된 프리뷰 주소. **이전 주소는 즉시 무효**가 되므로 "
                + "iframe에는 반드시 이 값을 사용한다",
                example = "https://qeploy.com/api/v1/previews/3f1a.../abcdef.../")
        String previewUrl,

        @Schema(description = "세션 만료 시각. 이 시각 이후에는 다시 발급받아야 한다")
        LocalDateTime expiresAt
) {

    public static PreviewAccessResponse from(PreviewAccessGrant grant) {
        return new PreviewAccessResponse(grant.sessionId(), grant.previewUrl(), grant.expiresAt());
    }
}
