-- DOCKER 배포 이미지를 어디서 빌드했는지(#556). CONTROL_PLANE | TARGET, NATIVE 배포와 이전 행은 NULL.
-- 상태 워커가 이 값으로 기동 기한을 정한다 — TARGET 은 부팅 중 이미지 빌드 시간이 더해진다.
ALTER TABLE provisioned_servers
    ADD COLUMN image_build_location VARCHAR(20) NULL AFTER boot_diagnostics;
