package com.example.dvely.provisioning.domain.value;

/**
 * 이 DB 자원을 누가 마련했는지. FE 가 목록에서 "사용자가 만든 것"과 "프리뷰가 자동으로 마련한 것"을
 * 구분해 표시할 수 있게 한다 — 자동 DB 를 사용자가 "내가 안 만들었는데?"로 오해하거나 중복 생성하는
 * 것을 막는다.
 */
public enum ProvisionOrigin {
    MANUAL,        // 사용자가 인프라 탭에서 직접 생성
    PREVIEW_AUTO   // 서버형 프리뷰 부팅 시 자동 프로비저닝
}
