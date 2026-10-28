package com.example.dvely.common.limit;

import com.example.dvely.common.response.ErrorCode;

/**
 * 사용자별 동시 실행 상한에 걸렸을 때(#509). 요청은 정상이지만 지금은 받을 수 없다 — 진행 중인 것이 끝나거나
 * 닫히면 다시 받는다. 메시지는 사용자에게 그대로 보여 줄 문장이다.
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
