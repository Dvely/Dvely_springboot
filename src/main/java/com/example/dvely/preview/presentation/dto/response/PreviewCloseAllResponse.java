package com.example.dvely.preview.presentation.dto.response;

import com.example.dvely.preview.application.result.BulkPreviewCloseResult;
import java.util.List;

/** {@code DELETE /api/v1/agent/session} 의 응답. 닫은 수와, 작업이 쓰고 있어 남긴 프리뷰(#650). */
public record PreviewCloseAllResponse(int closed, List<InUsePreviewResponse> kept) {

    public static PreviewCloseAllResponse from(BulkPreviewCloseResult result) {
        return new PreviewCloseAllResponse(result.closed(), InUsePreviewResponse.from(result.kept()));
    }
}
