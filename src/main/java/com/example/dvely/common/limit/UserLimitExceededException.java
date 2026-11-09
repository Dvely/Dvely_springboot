package com.example.dvely.common.limit;

import com.example.dvely.common.response.ErrorCode;

/**
 * 동시 실행 상한에 걸렸을 때 — 사용자별(#509)과 서버 전체(#624). 요청은 정상이지만 지금은 받을 수 없다 — 진행 중인
 * 것이 끝나거나 닫히면 다시 받는다. 메시지는 사용자에게 그대로 보여 줄 문장이다. 어느 상한인지는 {@link #errorCode()} 로
 * 가른다.
 */
public class UserLimitExceededException extends RuntimeException {

    private final ErrorCode errorCode;

    public UserLimitExceededException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
