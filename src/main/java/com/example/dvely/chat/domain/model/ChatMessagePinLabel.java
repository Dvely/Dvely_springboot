package com.example.dvely.chat.domain.model;

/** 메시지 목록에 보이는 짚은 곳 칩. 짚기 기술 본문(최대 48KiB)은 읽지 않는다. */
public record ChatMessagePinLabel(int position, String label) {
}
