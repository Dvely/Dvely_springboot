package com.example.dvely.deviceshare.domain.value;

/** 피드백에 이어진 대화의 종류. */
public enum FeedbackNoteKind {
    /** 고쳤다는 메모 */
    FIXED,
    /** 짚은 사람이 다시 열며 붙인 글 */
    REOPEN,
    /** 짚은 사람(또는 주인)이 확인했다 */
    CONFIRM
}
