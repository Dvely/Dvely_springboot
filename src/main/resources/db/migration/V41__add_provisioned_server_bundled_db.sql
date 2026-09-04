-- 번들 DB 엔진(NULL=없음). 값이 있으면 DOCKER 배포가 같은 EC2 에 이 엔진(MYSQL/POSTGRESQL)의 DB
-- 컨테이너를 docker compose 로 함께 띄우고 앱을 그 DB 로 배선한다 — RDS 없이 앱+DB 한 인스턴스("올인원").
-- 기존 서버는 번들 DB 가 없었으므로 NULL(=없음)로 둔다. deploy_mode 바로 뒤에 놓는다.
ALTER TABLE provisioned_servers
    ADD COLUMN bundled_db_engine VARCHAR(20) NULL AFTER deploy_mode;
