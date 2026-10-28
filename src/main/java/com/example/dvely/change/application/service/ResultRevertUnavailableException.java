package com.example.dvely.change.application.service;

/** 서버 측 git 이 꺼진 환경에서 거절 REVERT 를 요청했다(#532). 승인은 PENDING 그대로다. 메시지는 사용자에게 그대로 보인다. */
public class ResultRevertUnavailableException extends RuntimeException {

    public ResultRevertUnavailableException() {
        super("이 환경에서는 preview 되돌리기를 쓸 수 없습니다. 'preview 에 남기기'로 거절해 주세요.");
    }
}
