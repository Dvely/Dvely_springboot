package com.example.dvely.preview.application.result;

import java.util.List;

/**
 * 열린 프리뷰를 한꺼번에 닫은 결과(#650). 진행 중이거나 결정을 기다리는 작업의 프리뷰는 닫지 않고 {@code kept} 에 남긴다.
 *
 * @param closed 닫은 수
 * @param kept   닫지 않은 프리뷰
 */
public record BulkPreviewCloseResult(int closed, List<InUsePreview> kept) {

    public BulkPreviewCloseResult {
        kept = List.copyOf(kept);
    }
}
