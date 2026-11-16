package com.example.dvely.deviceshare.presentation.dto;

/** 에이전트에게 넘길 Markdown(설계 §14.5). 이미지 줄은 없다 — CLI 는 파일 경로를, MCP 는 이미지 내용을 붙인다. */
public record ShareFeedbackPromptResponse(String markdown) {
}
