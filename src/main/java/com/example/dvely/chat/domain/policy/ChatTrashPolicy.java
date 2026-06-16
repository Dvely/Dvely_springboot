package com.example.dvely.chat.domain.policy;

import java.time.Duration;
import java.time.LocalDateTime;

public final class ChatTrashPolicy {

    public static final int RETENTION_DAYS = 7;
    private static final long SECONDS_PER_DAY = Duration.ofDays(1).getSeconds();

    private ChatTrashPolicy() {
    }

    public static LocalDateTime cutoff(LocalDateTime now) {
        return now.minusDays(RETENTION_DAYS);
    }

    public static LocalDateTime expiresAt(LocalDateTime deletedAt) {
        return deletedAt == null ? null : deletedAt.plusDays(RETENTION_DAYS);
    }

    public static boolean isExpired(LocalDateTime deletedAt, LocalDateTime now) {
        LocalDateTime expiresAt = expiresAt(deletedAt);
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public static Integer remainingDays(LocalDateTime deletedAt, LocalDateTime now) {
        LocalDateTime expiresAt = expiresAt(deletedAt);
        if (expiresAt == null) {
            return null;
        }
        long remainingSeconds = Duration.between(now, expiresAt).getSeconds();
        if (remainingSeconds <= 0) {
            return 0;
        }
        return Math.toIntExact((remainingSeconds + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY);
    }
}
