package com.example.dvely.agent.application.service;

import java.util.Optional;

/**
 * CODE 단계의 맺음말을 어느 말로 쓸지 지시에 못박는다.
 *
 * <p>CODE 에이전트(툴 루프든 Claude Code 든)는 "사용자가 쓴 말로" 요약하라는 규칙을 이미 받는다. 그런데 에이전트가
 * 보는 것은 사용자 메시지가 아니라 계획 모델이 다시 쓴 지시뿐이라, 계획 모델이 한국어 요청을 영어 지시로 옮기면
 * 요약이 영어로 나온다(2026-11-26 실측 — "I made the new page you asked for…"). 계획 프롬프트도 같은 말로 쓰라고
 * 적고 있지만 늘 지키지는 않는다.</p>
 *
 * <p>계획의 {@code userSummary} 는 승인 카드에 사람에게 보이는 문장이라 사용자 말로 쓰인다(같은 실측에서도 지시는
 * 영어, userSummary 는 한국어였다). 그 문장의 글자로 말을 정해 지시 끝에 붙인다. 정할 수 없으면 아무것도 붙이지 않고
 * 지금 규칙(지시의 말)에 맡긴다.</p>
 */
final class ReplyLanguage {

    private ReplyLanguage() {
    }

    /** @return 사람이 쓴 말의 영어 이름(Korean·Japanese·Chinese), 정할 수 없으면 빈 값 */
    static Optional<String> of(String userSummary) {
        if (userSummary == null || userSummary.isBlank()) {
            return Optional.empty();
        }
        boolean kana = false;
        boolean han = false;
        for (int i = 0; i < userSummary.length(); ) {
            int cp = userSummary.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HANGUL) {
                return Optional.of("Korean");
            }
            kana |= script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA;
            han |= script == Character.UnicodeScript.HAN;
            i += Character.charCount(cp);
        }
        if (kana) {
            return Optional.of("Japanese");
        }
        return han ? Optional.of("Chinese") : Optional.empty();
    }

    /** 지시 끝에 붙일 한 단락. 말을 정할 수 없으면 지시 그대로 */
    static String append(String instruction, String userSummary) {
        return of(userSummary)
                .map(language -> instruction + "\n\nReply language: the person who asked wrote in " + language
                        + ". Write your closing summary for them in " + language
                        + ", even though this instruction may be in another language.")
                .orElse(instruction);
    }
}
