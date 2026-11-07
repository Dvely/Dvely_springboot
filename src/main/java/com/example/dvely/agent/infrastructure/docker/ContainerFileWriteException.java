package com.example.dvely.agent.infrastructure.docker;

/**
 * 컨테이너에 파일을 쓰지 못했다(#617). 메시지는 그대로 모델에게 tool 결과로 돌아간다 — 모델이 읽고 경로를 고치거나
 * 다른 방법을 고를 수 있게 원인을 적는다. <b>파일 내용은 메시지에 넣지 않는다</b>(로그와 실패 요약에도 실린다).
 */
public class ContainerFileWriteException extends RuntimeException {

    public ContainerFileWriteException(String message) {
        super(message);
    }

    public ContainerFileWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
