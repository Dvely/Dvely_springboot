package com.example.dvely.template.application.exception;

/**
 * 카탈로그를 한 번도 읽지 못한 상태에서 조회가 들어온 경우.
 *
 * 갱신에 실패했더라도 직전에 읽어둔 것이 있으면 그것을 쓰므로(stale-while-error) 이 예외는
 * "아직 한 번도 성공하지 못했다"는 뜻이다. 이때 요청을 통과시키면 존재하지 않는 템플릿이
 * 프로젝트에 저장되고, 씨딩 시점에 가서야 깨진다 — 그래서 조용히 넘기지 않고 여기서 끊는다.
 */
public class TemplateCatalogUnavailableException extends RuntimeException {

    public TemplateCatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public TemplateCatalogUnavailableException(String message) {
        super(message);
    }
}
