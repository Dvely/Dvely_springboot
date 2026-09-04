package com.example.dvely.deployment.application.port.out;

import java.util.List;

/**
 * 프론트엔드를 사용자 EC2 에서(nginx 컨테이너) 호스팅하는 포트 — 독립 프론트 EC2. GitHub Pages·S3 와
 * 달리 EC2 는 비동기·과금·장수 자원이라 배포가 동기로 끝나지 않고 <b>승인 게이트</b>를 거쳐 웹 전용
 * 서버로 프로비저닝된다(provisioning 도메인의 서버 흐름 재사용). 구현 어댑터가 그 위임을 한다.
 */
public interface FrontendServerHostingPort {

    /**
     * 웹 전용(백엔드 없는 프론트 nginx) EC2 서버를 프로비저닝 요청한다. 과금 자원이라 승인이 필요하므로
     * 즉시 뜨지 않고 대기 서버 id 와 승인 id 를 돌려준다(서버 흐름과 동형).
     */
    ServerSubmission provisionWebOnly(Request request);

    /**
     * @param projectId   대상 프로젝트
     * @param ownerUserId 소유자
     * @param frontendRepo 프론트 소스 저장소({@code owner/repo}). 보통 프로젝트의 소스 저장소(레포 루트가
     *                     프론트) — S3 경로와 같은 가정.
     */
    record Request(Long projectId, Long ownerUserId, String frontendRepo) {}

    /** @param approvalIds 생성된 승인 id(FE 가 승인 화면으로 연결). */
    record ServerSubmission(Long serverId, List<Long> approvalIds) {}
}
