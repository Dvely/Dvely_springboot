package com.example.dvely.deviceshare.application.session;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 기기가 푼 짚기 결과를 서버가 고른 것(설계 §14.3).
 *
 * @param probeId     짚기 id
 * @param resolved    요소를 찾았는지. 못 찾아도 점과 이미지로 보낼 수 있다
 * @param reason      못 찾은 까닭({@code NO_ELEMENT} · {@code UNSUPPORTED} · {@code TIMEOUT}), 찾았으면 null
 * @param description 저장할 요소 기술. 아는 필드만 있고 길이가 잘려 있다
 * @param screenLabel 화면 이름(페이지 경로 · 패키지/Activity · 번들 ID). 없으면 빈 문자열
 * @param bounds      요소 테두리(비율). 없으면 null
 * @param label       뷰어에 보일 요소 이름. 없으면 null
 * @param component   가장 안쪽 컴포넌트 이름. 없으면 null
 */
public record PinResolution(String probeId, boolean resolved, String reason, ObjectNode description,
                            String screenLabel, ObjectNode bounds, String label, String component) {
}
