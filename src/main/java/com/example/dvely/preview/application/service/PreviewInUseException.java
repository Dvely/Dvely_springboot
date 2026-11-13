package com.example.dvely.preview.application.service;

import com.example.dvely.agent.application.dto.TaskStatus;
import com.example.dvely.preview.application.result.InUsePreview;
import java.util.List;

/**
 * 진행 중이거나 결정을 기다리는 작업이 쓰는 프리뷰라 닫지 않았다(#650). 아무것도 닫지 않은 상태다.
 *
 * <p>409 {@code PREVIEW_IN_USE} 로 나가고, 응답 {@code data.blocked} 에 막은 세션이 실린다. 메시지는 막은 작업의
 * 상태에 맞춰 다음 행동을 적는다.</p>
 */
public class PreviewInUseException extends RuntimeException {

    private final transient List<InUsePreview> blocked;

    public PreviewInUseException(List<InUsePreview> blocked) {
        super(message(blocked));
        this.blocked = List.copyOf(blocked);
    }

    public List<InUsePreview> blocked() {
        return blocked;
    }

    private static String message(List<InUsePreview> blocked) {
        boolean awaitingResult = blocked.stream()
                .anyMatch(preview -> preview.taskStatus() == TaskStatus.WAITING_RESULT_APPROVAL);
        boolean otherwise = blocked.stream()
                .anyMatch(preview -> preview.taskStatus() != TaskStatus.WAITING_RESULT_APPROVAL);
        StringBuilder message = new StringBuilder("진행 중이거나 결정을 기다리는 작업이 쓰는 프리뷰라 닫을 수 없습니다.");
        if (awaitingResult) {
            // 승인하면 반영되고, 거절하거나 취소하면 작업이 끝나 닫을 수 있다.
            message.append(" 결과 승인을 먼저 결정해 주세요.");
        }
        if (otherwise) {
            message.append(" 작업이 끝나거나 작업을 취소한 뒤 닫을 수 있습니다.");
        }
        return message.toString();
    }
}
