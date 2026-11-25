package com.example.dvely.chat.application.pin;

import java.util.Map;

/**
 * 요청에 실려 온 짚은 곳 하나. 브리지가 낸 결과를 FE 가 그대로 넘긴 것이라 아직 아무것도 믿지 않는다(설계 §16.2).
 *
 * <p>HTTP 매퍼(Jackson 3)와 짚기 검사기(Jackson 2 트리)가 달라 경계에서는 맵으로 받는다 — 짚기 피드백 상세 응답과 같은 방식.</p>
 *
 * @param result 브리지의 짚기 결과(§14.3·§15 모양)
 * @param scope  사람이 고른 사다리 칸. 없으면 결과의 기본 칸
 * @param peers  고른 칸의 "같은 것 모두"(§15.3). 고른 칸에 같은 것이 없으면 무시한다
 */
public record PreviewPinInput(Map<String, Object> result, Integer scope, Boolean peers) {

    public PreviewPinInput(Map<String, Object> result, Integer scope) {
        this(result, scope, null);
    }
}
