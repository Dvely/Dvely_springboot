package com.example.dvely.preview.presentation.dto.response;

import com.example.dvely.preview.application.result.InUsePreview;
import java.util.List;

/** 409 {@code PREVIEW_IN_USE} 의 {@code data}. 아무것도 닫지 않았고, 막은 세션이 {@code blocked} 에 있다(#650). */
public record PreviewInUseResponse(List<InUsePreviewResponse> blocked) {

    public static PreviewInUseResponse from(List<InUsePreview> blocked) {
        return new PreviewInUseResponse(InUsePreviewResponse.from(blocked));
    }
}
