package com.example.dvely.common.exception;

import java.util.regex.Pattern;

/**
 * 응답 본문에 실을 예외 메시지에서 내부 식별자·상태값 꼬리(`. taskId=abc status=FAILED`)를 뗀다.
 *
 * <p>예외 메시지는 로그와 응답이 함께 쓴다. 꼬리는 로그에서 원인을 좇는 데 필요하지만, FE 는 메시지를 화면 문구로 그대로
 * 보여 주므로 사용자에게는 개발자 문구가 된다. 그래서 던지는 쪽은 그대로 두고 응답을 만드는 한 곳에서 뗀다 — 로그에는
 * 원문이 남는다.</p>
 *
 * <p>떼는 것은 끝에 붙은, 아래 키의 꼬리뿐이다. 사용자에게 의미가 있는 값(환경변수 키·저장소·브랜치 이름)이나 실패
 * 진단(exitCode 등)은 키가 목록에 없어 남는다. 문장 가운데의 값도 건드리지 않는다.</p>
 */
public final class UserFacingMessage {

    private static final Pattern INTERNAL_TAIL = Pattern.compile(
            "(?:[\\s,(]*\\b(?:taskId|approvalId|sessionId|changeId|domainId|projectId|ownerUserId|userId|status|id|"
                    + "provider|databaseId|conversationId|deploymentId|historyId|serverId|connectionId|environmentId)"
                    + "=[^\\s,()]*\\)?)+\\s*$");

    private UserFacingMessage() {
    }

    public static String of(String message) {
        if (message == null) {
            return null;
        }
        String stripped = INTERNAL_TAIL.matcher(message).replaceFirst("");
        return stripped.isBlank() ? message : stripped;
    }
}
