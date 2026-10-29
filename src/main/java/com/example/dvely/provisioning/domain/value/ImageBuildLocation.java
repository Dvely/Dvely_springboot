package com.example.dvely.provisioning.domain.value;

/**
 * DOCKER 배포 이미지를 어디서 빌드했는지(#545·#556). 배포할 때 정해 서버에 남긴다 — 상태 워커는 이 값으로 기동
 * 기한을 계산하므로, 설정이 그 사이 바뀌어도 이미 떠 있는 서버의 판단은 흔들리지 않는다. NATIVE 배포는 null.
 */
public enum ImageBuildLocation {
    /** 컨트롤 플레인이 빌드해 S3·ECR 로 전달했다 */
    CONTROL_PLANE,
    /** 배포 대상 EC2 가 부팅하며 직접 빌드했다 */
    TARGET
}
