package com.example.dvely.change.application.service;

/** 거절 REVERT 의 되돌리는 커밋을 올리지 못했다(#532). 승인은 PENDING 그대로다. 메시지는 사용자에게 그대로 보인다. */
public class ResultRevertFailedException extends RuntimeException {

    public ResultRevertFailedException(Throwable cause) {
        super("preview 를 되돌리지 못해 거절하지 않았습니다. 잠시 후 다시 시도하거나 'preview 에 남기기'로 거절해 주세요.", cause);
    }
}
