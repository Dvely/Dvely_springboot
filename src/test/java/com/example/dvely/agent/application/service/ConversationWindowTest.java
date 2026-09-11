package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.application.port.out.LlmMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationWindowTest {

    @Test
    void keepsTheMostRecentTurnsAndDropsTheOldest() {
        List<LlmMessage> history = turns(50);

        List<LlmMessage> windowed = ConversationWindow.apply(history);

        assertThat(windowed).hasSize(ConversationWindow.MAX_TURNS);
        assertThat(windowed.get(windowed.size() - 1).content()).isEqualTo("turn-49");
        assertThat(windowed.get(0).content()).isEqualTo("turn-30");
    }

    @Test
    void alsoStopsOnTheCharacterBudgetSoOneHugeTurnCannotFillTheWindow() {
        List<LlmMessage> history = new ArrayList<>();
        history.add(new LlmMessage("user", "x".repeat(30_000)));
        history.add(new LlmMessage("assistant", "y".repeat(30_000)));
        history.add(new LlmMessage("user", "지금 처리할 요청"));

        List<LlmMessage> windowed = ConversationWindow.apply(history);

        assertThat(windowed).hasSize(1);
        assertThat(windowed.get(0).content()).isEqualTo("지금 처리할 요청");
    }

    @Test
    void neverDropsTheLastTurnEvenWhenItAloneExceedsTheBudget() {
        // 마지막 턴은 지금 처리할 요청 그 자체다. 버리면 무엇을 하라는 것인지가 사라진다.
        List<LlmMessage> history = List.of(
                new LlmMessage("user", "옛 요청"),
                new LlmMessage("user", "z".repeat(ConversationWindow.MAX_CHARS * 2)));

        List<LlmMessage> windowed = ConversationWindow.apply(history);

        assertThat(windowed).hasSize(1);
        assertThat(windowed.get(0).content()).hasSize(ConversationWindow.MAX_CHARS * 2);
    }

    @Test
    void keepsShortConversationsExactlyAsTheyWere() {
        List<LlmMessage> history = turns(5);

        assertThat(ConversationWindow.apply(history)).isEqualTo(history);
    }

    @Test
    void preservesChronologicalOrder() {
        List<LlmMessage> windowed = ConversationWindow.apply(turns(40), 3, 10_000);

        assertThat(windowed.stream().map(LlmMessage::content))
                .containsExactly("turn-37", "turn-38", "turn-39");
    }

    @Test
    void handlesAnEmptyOrNullHistory() {
        assertThat(ConversationWindow.apply(List.of())).isEmpty();
        assertThat(ConversationWindow.apply(null)).isEmpty();
    }

    private static List<LlmMessage> turns(int count) {
        List<LlmMessage> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(new LlmMessage(i % 2 == 0 ? "user" : "assistant", "turn-" + i));
        }
        return List.copyOf(history);
    }
}
