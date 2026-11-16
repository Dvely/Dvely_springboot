package com.example.dvely.deviceshare.application.service;

import static com.example.dvely.deviceshare.domain.value.FeedbackStatus.ACCEPTED;
import static com.example.dvely.deviceshare.domain.value.FeedbackStatus.CONFIRMED;
import static com.example.dvely.deviceshare.domain.value.FeedbackStatus.DISMISSED;
import static com.example.dvely.deviceshare.domain.value.FeedbackStatus.FIXED;
import static com.example.dvely.deviceshare.domain.value.FeedbackStatus.NEW;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 상태가 바뀌는 길(설계 §14.2). 받기 전에는 고칠 수 없고, 확인한 것은 끝이다. */
class FeedbackStatusTest {

    @Test
    void 받기_전에는_고침으로_갈_수_없다() {
        assertThat(NEW.canMoveTo(ACCEPTED)).isTrue();
        assertThat(NEW.canMoveTo(DISMISSED)).isTrue();
        assertThat(NEW.canMoveTo(FIXED)).isFalse();
        assertThat(NEW.canMoveTo(CONFIRMED)).isFalse();
    }

    @Test
    void 고친_것은_확인하거나_다시_연다() {
        assertThat(ACCEPTED.canMoveTo(FIXED)).isTrue();
        assertThat(FIXED.canMoveTo(CONFIRMED)).isTrue();
        assertThat(FIXED.canMoveTo(NEW)).isTrue();
        assertThat(FIXED.canMoveTo(ACCEPTED)).isTrue();
        assertThat(DISMISSED.canMoveTo(ACCEPTED)).isTrue();
        assertThat(DISMISSED.canMoveTo(FIXED)).isFalse();
        for (var next : com.example.dvely.deviceshare.domain.value.FeedbackStatus.values()) {
            assertThat(CONFIRMED.canMoveTo(next)).isFalse();
        }
    }

    @Test
    void 에이전트는_받은_것만_본다() {
        assertThat(NEW.agentVisible()).isFalse();
        assertThat(DISMISSED.agentVisible()).isFalse();
        assertThat(ACCEPTED.agentVisible()).isTrue();
        assertThat(FIXED.agentVisible()).isTrue();
        assertThat(CONFIRMED.agentVisible()).isTrue();
    }
}
