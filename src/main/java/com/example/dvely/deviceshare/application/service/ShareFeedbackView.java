package com.example.dvely.deviceshare.application.service;

import com.example.dvely.deviceshare.domain.model.ShareFeedback;
import com.example.dvely.deviceshare.domain.model.ShareFeedbackNote;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 주인 API 가 보여 줄 피드백 하나.
 *
 * @param element   저장된 요소 기술(§14.3)
 * @param label     요소 이름(예: {@code <button> "저장"}), 없으면 null
 * @param component 가장 안쪽 컴포넌트 이름, 없으면 null
 * @param notes     상세에서만 채운다
 */
public record ShareFeedbackView(ShareFeedback feedback, JsonNode element, String label, String component,
                                List<ShareFeedbackNote> notes, boolean hasCrop, boolean hasScreen) {
}
