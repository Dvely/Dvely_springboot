package com.example.dvely.deployment.domain.value;

public enum DeployTargetType {
    LATEST,   // 최신 작업 기준 (default branch HEAD)
    VERSION   // git tag 기준
}
