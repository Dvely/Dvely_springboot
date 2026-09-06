package com.example.dvely.project.domain.value;

/**
 * 프로젝트의 프론트엔드를 어디에 호스팅할지. 프로젝트 단위 설정이라 매 배포마다 다시 고르지 않아도
 * 되고, 재배포·정리·도메인 바인딩이 "이 프로젝트 프론트가 어디 있는지"를 이 값 하나로 안다.
 *
 * <p>도메인 바인딩의 {@code DomainHostingTarget}(GITHUB_PAGES/AWS/GCP)과는 <b>별개 축</b>이다.
 * 그쪽 {@code AWS}는 이미 "EC2 백엔드(A레코드→EIP)"를 의미해 CORS 분류·백엔드 도메인 정리에 박혀
 * 있으므로, 프론트-on-S3 를 그 {@code AWS}로 재사용하면 프론트가 백엔드로 오분류된다. 그래서 프론트
 * 호스팅은 여기 별도 enum 으로 두고, 도메인 바인딩 타깃은 건드리지 않는다.</p>
 */
public enum FrontendHostingType {

    /** 기존 기본값 — GitHub Actions 가 빌드해 gh-pages 브랜치에 발행. deployment 도메인이 담당. */
    GITHUB_PAGES,

    /** 서버측에서 빌드한 정적 산출물(dist)을 사용자 AWS 계정의 S3 정적 웹호스팅 버킷에 올린다. */
    S3,

    /** 정적 산출물을 사용자 EC2 안에서(nginx) 서빙한다. */
    EC2
}
