package com.example.dvely.chat.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ChatTrashPolicyTest {

    @Test
    void usesSevenDayRetentionAndRoundsRemainingDaysUp() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 12, 10, 0);
        LocalDateTime deletedAt = now.minusDays(1).minusHours(1);

        assertThat(ChatTrashPolicy.expiresAt(deletedAt))
                .isEqualTo(LocalDateTime.of(2026, 6, 18, 9, 0));
        assertThat(ChatTrashPolicy.remainingDays(deletedAt, now)).isEqualTo(6);
        assertThat(ChatTrashPolicy.isExpired(deletedAt, now)).isFalse();
        assertThat(ChatTrashPolicy.isExpired(now.minusDays(7), now)).isTrue();
    }
}
