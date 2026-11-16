package com.example.dvely.deviceshare.domain.value;

import java.util.EnumSet;
import java.util.Set;

/**
 * 짚기 피드백 상태(설계 §14.2).
 *
 * <p>손님 글은 {@link #ACCEPTED} 가 되어야 에이전트(PAT)로 나간다. 다시 열기는 손님 글이 새로 붙으므로 {@link #NEW} 로
 * 돌아가 다시 받아야 한다.</p>
 */
public enum FeedbackStatus {
    /** 손님이 남겼고 주인이 아직 보지 않았다 */
    NEW,
    /** 주인이 받았다. 에이전트에게 나간다 */
    ACCEPTED,
    /** 받지 않았다 */
    DISMISSED,
    /** 고쳤다고 표시했다 */
    FIXED,
    /** 짚은 사람이 고친 화면을 보고 확인했다 */
    CONFIRMED;

    /** PAT(에이전트)가 읽을 수 있는 상태. 받기 전 손님 글과 받지 않은 글은 사람만 본다. */
    public static final Set<FeedbackStatus> AGENT_VISIBLE = EnumSet.of(ACCEPTED, FIXED, CONFIRMED);

    public boolean canMoveTo(FeedbackStatus next) {
        return switch (this) {
            case NEW -> next == ACCEPTED || next == DISMISSED;
            case DISMISSED -> next == ACCEPTED;
            case ACCEPTED -> next == FIXED || next == DISMISSED;
            // 손님이 다시 열면 NEW(글이 새로 붙어 다시 받아야 한다), 주인이 다시 열면 ACCEPTED
            case FIXED -> next == CONFIRMED || next == NEW || next == ACCEPTED;
            case CONFIRMED -> false;
        };
    }

    public boolean agentVisible() {
        return AGENT_VISIBLE.contains(this);
    }
}
