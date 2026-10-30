package com.example.dvely.common.exception;

import java.util.List;

/**
 * JDK·라이브러리가 만든 IllegalArgumentException·IllegalStateException 의 메시지는 응답에 싣지 않는다(#511 B7).
 *
 * <p>{@code Integer.parseInt} 의 "For input string: \"abc\"", {@code Enum.valueOf} 의 "No enum constant com.example…"
 * 처럼 그 문구는 영어이고 클래스 경로까지 담는다. FE 는 message 를 화면 문구로 그대로 보여 주므로 고정 문장으로 바꾼다.
 * 우리가 직접 쓴 한글 메시지는 그대로 둔다 — 일괄로 숨기면 "실패한 배포만 재시도할 수 있습니다" 같은 안내까지 사라진다.
 * 로그에는 원문이 남는다.</p>
 *
 * <p>판별: 하위 클래스(NumberFormatException 등)는 우리 패키지 것이 아니면 JDK·라이브러리 출처다. 두 예외 그 자체라면
 * 던진 자리(스택 맨 위)가 JDK 클래스일 때 JDK 출처다({@code Enum.valueOf}·{@code UUID.fromString}).</p>
 */
final class JdkExceptionMessage {

    static final String BAD_REQUEST = "요청 값의 형식이 올바르지 않습니다.";
    static final String CONFLICT = "지금은 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.";

    private static final String OWN_PACKAGE = "com.example.dvely.";
    private static final List<String> JDK_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    private JdkExceptionMessage() {
    }

    static boolean isFromJdk(RuntimeException e) {
        Class<?> type = e.getClass();
        if (type != IllegalArgumentException.class && type != IllegalStateException.class) {
            return !type.getName().startsWith(OWN_PACKAGE);
        }
        StackTraceElement[] trace = e.getStackTrace();
        return trace.length > 0 && JDK_PACKAGES.stream().anyMatch(trace[0].getClassName()::startsWith);
    }
}
