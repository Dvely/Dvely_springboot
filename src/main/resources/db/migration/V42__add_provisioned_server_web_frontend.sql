-- 웹(프론트) 컨테이너: 값이 있으면 같은 EC2 에 프론트 nginx 컨테이너를 compose 로 함께 띄운다
-- (back+web+db "올인원", 같은 오리진). frontend_repo=별도 프론트 레포(split), frontend_dir=백엔드 레포의
-- 프론트 하위폴더(모노). 둘 중 하나라도 있으면 웹 컨테이너 활성. api_path_prefix=백엔드 API 프리픽스
-- (nginx 가 이걸 app 으로 프록시, 나머지는 SPA 폴백; 비면 /api). 기존 서버는 전부 NULL(=웹 없음).
ALTER TABLE provisioned_servers
    ADD COLUMN frontend_repo   VARCHAR(255) NULL AFTER bundled_db_engine,
    ADD COLUMN frontend_dir    VARCHAR(255) NULL AFTER frontend_repo,
    ADD COLUMN api_path_prefix VARCHAR(255) NULL AFTER frontend_dir;
