package com.example.dvely.project.domain.value;

public enum DeployStatus {
    DRAFT,
    PENDING,      // 배포 요청이 영속 queue에서 worker 실행을 기다리는 중
    IN_PROGRESS,  // 빌드/배포 진행 중 (GitHub Actions 실행 중)
    PREVIEW_READY,
    LIVE,
    FAILED
}
