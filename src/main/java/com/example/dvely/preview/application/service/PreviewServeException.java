package com.example.dvely.preview.application.service;

/**
 * 빌드는 됐지만 서버를 띄우거나 응답을 받지 못했을 때(포트 미응답, gradle 타임아웃, 잘못된 컨테이너
 * 크기 등). 빌드 산출물 자체가 없는 실패("빌드 결과 디렉터리를 찾지 못했습니다")와 구분한다 —
 * 후자는 LLM 이 빌드를 고칠 여지가 있어 재시도가 의미 있지만, 이쪽은 코드를 다시 생성해도 안
 * 고쳐지므로 재시도로 예산을 태우지 않게 호출자가 다르게 처리한다.
 */
public class PreviewServeException extends RuntimeException {
    public PreviewServeException(String message) {
        super(message);
    }
}
