package com.example.dvely.project.domain.value;

public enum DeployStatus {
    DRAFT,
    IN_PROGRESS,  // 빌드/배포 진행 중 (GitHub Actions 실행 중)
    PREVIEW_READY,
    LIVE,
    FAILED
}