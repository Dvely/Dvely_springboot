package com.example.dvely.chat.domain.model;

/**
 * 서버 프리뷰에서 짚어 사용자 메시지에 붙인 곳 하나(설계 §16.6).
 *
 * @param position    ①~⑤ 번호(1~5). 보낸 순서
 * @param label       칩에 보이는 이름 — 고른 범위의 이름
 * @param elementJson 서버가 PinResultSanitizer 로 다시 만든 짚기 기술. 고른 칸은 {@code scope}
 */
public record ChatMessagePin(int position, String label, String elementJson) {
}
