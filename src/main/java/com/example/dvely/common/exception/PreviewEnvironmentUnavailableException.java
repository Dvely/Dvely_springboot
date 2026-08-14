package com.example.dvely.common.exception;

/**
 * 프리뷰 컨테이너를 띄울 실행 환경(Docker)에 접근하지 못했을 때.
 *
 * <p>요청 자체는 정상이고 서버 코드에도 결함이 없다 — 그 서버에 Docker 가 없거나, 앱 계정이
 * `/var/run/docker.sock` 에 접근할 수 없거나, 데몬이 죽어 있는 상태다({@code deploy/README.md} §4:
 * "빠뜨려도 앱은 정상 기동한다 … 컨테이너를 띄우려는 순간 실패"). 이 구분이 없으면 전부
 * catch-all 500 "서버 내부 오류"로 뭉뚱그려져, FE 도 운영자도 서버에 직접 붙어보기 전에는 원인을
 * 알 수 없다. 503 과 이 메시지가 그 확인을 대신한다.</p>
 */
public class PreviewEnvironmentUnavailableException extends RuntimeException {

    public PreviewEnvironmentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
