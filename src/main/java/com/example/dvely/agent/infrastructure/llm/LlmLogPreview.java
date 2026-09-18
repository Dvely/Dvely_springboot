package com.example.dvely.agent.infrastructure.llm;

/**
 * 제공자 응답 원문을 로그에 실을 만큼만 자른다.
 *
 * <p>파싱 실패 로그는 응답 <b>전체</b>를 찍고 있었다. 그 안에는 모델이 방금 쓴 소스 코드와
 * 사용자가 무엇을 만들라고 했는지가 그대로 들어 있어서, 파싱이 한 번 어긋날 때마다 사용자의
 * 코드와 요청이 운영 로그 수집기로 흘러나갔다. 무엇이 어긋났는지 보는 데는 앞부분이면 된다.</p>
 */
final class LlmLogPreview {

    private static final int MAX_CHARS = 300;

    private LlmLogPreview() {
    }

    static String of(String raw) {
        if (raw == null) {
            return "(없음)";
        }
        return raw.length() <= MAX_CHARS
                ? raw
                : raw.substring(0, MAX_CHARS) + "…(" + raw.length() + "자 중 앞부분)";
    }
}
