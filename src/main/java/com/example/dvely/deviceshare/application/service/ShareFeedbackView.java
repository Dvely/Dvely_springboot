package com.example.dvely.deviceshare.application.service;

import com.example.dvely.deviceshare.domain.model.ShareFeedback;
import com.example.dvely.deviceshare.domain.model.ShareFeedbackNote;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 주인 API 가 보여 줄 피드백 하나.
 *
 * @param element      저장된 요소 기술(§14.3·§15.10)
 * @param label        고른 범위의 이름(여러 곳이면 "① … 외 N곳"), 없으면 null
 * @param component    가장 안쪽 컴포넌트 이름, 없으면 null
 * @param notes        상세에서만 채운다
 * @param hasAnnotated 짚은 곳마다 번호를 그린 화면이 있는지(여러 곳일 때)
 * @param marks        짚은 곳들 ①②…(상세에서만 채운다)
 */
public record ShareFeedbackView(ShareFeedback feedback, JsonNode element, String label, String component,
                                List<ShareFeedbackNote> notes, boolean hasCrop, boolean hasScreen,
                                boolean hasAnnotated, List<Mark> marks) {

    /**
     * 짚은 곳 하나(§15.5).
     *
     * @param n       번호(1부터)
     * @param label   그 곳에서 고른 범위의 이름
     * @param hasCrop 그 곳을 잘라 낸 그림이 있는지({@code …/image?kind=crop&mark=n})
     */
    public record Mark(int n, String label, boolean hasCrop) {
    }
}
