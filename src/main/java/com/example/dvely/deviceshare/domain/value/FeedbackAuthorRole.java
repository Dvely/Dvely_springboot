package com.example.dvely.deviceshare.domain.value;

/** 피드백·노트를 남긴 쪽. {@link #AGENT} 는 개인 액세스 토큰(MCP·CLI)으로 바꾼 것이다. */
public enum FeedbackAuthorRole {
    OWNER,
    GUEST,
    AGENT
}
