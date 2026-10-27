package com.example.dvely.agent.infrastructure.git;

/** 서버 측 git 작업 실패. 원인 예외의 메시지에 자격이 실리지 않도록 JGit 예외만 감싼다. */
public class ServerGitException extends RuntimeException {

    public ServerGitException(String message, Throwable cause) {
        super(message, cause);
    }
}
