package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.port.out.LlmMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * 대화 이력을 LLM 에 실을 만큼만 잘라 낸다.
 *
 * <p>여기까지는 상한이 없었다. {@code findAllByConversationIdOrderByCreatedAtAsc} 가 돌려준
 * 전량이 매 호출마다 그대로 실렸으므로, 오래 쓴 대화일수록 <b>모든</b> 요청이 비싸졌다 —
 * 대화가 길어질수록 비용이 선형으로 늘고, 언젠가는 컨텍스트 상한에 닿는다.</p>
 *
 * <p><b>이것은 동작 변경이다.</b> 창 밖으로 밀려난 앞부분을 에이전트는 더 이상 보지 못한다.
 * 긴 대화에서 초반에만 나온 사실(예: "이 앱은 사내용이야" 같은 전제)을 다시 말해야 할 수
 * 있다. 요약으로 접두를 대체하는 방법도 있지만 그것은 요약을 만들기 위한 LLM 호출을 하나 더
 * 늘리는 일이라, 비용을 줄이려는 이 작업에서는 택하지 않았다.</p>
 *
 * <p>두 가지 상한을 함께 건다. 턴 수만으로는 긴 코드 블록 하나가 창 전체를 삼키고, 글자 수만
 * 으로는 짧은 턴이 수백 개 쌓인 대화를 막지 못한다.</p>
 *
 * <p><b>항상 뒤에서부터</b> 담는다. 대화에서 지금 처리해야 할 요청은 언제나 마지막 턴이므로,
 * 넘칠 때 버려야 하는 것은 앞이다. 마지막 턴 하나는 글자 상한을 넘더라도 반드시 남긴다 —
 * 그것을 버리면 무엇을 하라는 요청인지 자체가 사라진다.</p>
 *
 * <p>별도 클래스인 이유: 호출부가 여러 곳이고 전부 다른 작업자가 동시에 손대는 파일이라,
 * 규칙이 그 파일들에 흩어지면 곧 서로 다른 창이 된다.</p>
 */
public final class ConversationWindow {

    /**
     * 남길 최근 턴 수.
     *
     * <p>20 은 사용자·어시스턴트가 번갈아 말하는 대화에서 주고받기 10회다. 실제로 한 요청이
     * 참조하는 맥락(직전 요청과 그 결과, 되묻기와 답)은 그보다 훨씬 짧고, 계획 수립 경로는
     * 사용자 발화만 싣기 때문에 같은 20이 사용자 발화 20개를 뜻한다.</p>
     */
    public static final int MAX_TURNS = 20;

    /**
     * 남길 최대 글자 수.
     *
     * <p>24,000자면 대략 6K 토큰이다. 대화 이력은 요청 하나의 <i>배경</i>일 뿐이고, 실제 작업
     * 맥락(파일 내용·빌드 로그)은 CODE 루프가 컨테이너에서 따로 읽는다.</p>
     */
    public static final int MAX_CHARS = 24_000;

    private ConversationWindow() {
    }

    public static List<LlmMessage> apply(List<LlmMessage> history) {
        return apply(history, MAX_TURNS, MAX_CHARS);
    }

    static List<LlmMessage> apply(List<LlmMessage> history, int maxTurns, int maxChars) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        List<LlmMessage> kept = new ArrayList<>();
        int chars = 0;
        for (int i = history.size() - 1; i >= 0 && kept.size() < maxTurns; i--) {
            LlmMessage message = history.get(i);
            int length = message.content() == null ? 0 : message.content().length();
            // 마지막 한 턴은 길어도 남긴다 — 그것이 지금 처리할 요청이다.
            if (!kept.isEmpty() && chars + length > maxChars) {
                break;
            }
            kept.add(message);
            chars += length;
        }

        java.util.Collections.reverse(kept);
        return List.copyOf(kept);
    }
}
