-- 백엔드-인-프리뷰 ① 후속: 서버형 자동 프로비저닝의 엔진 선택과, DB 자원의 마련 주체 구분.
--
-- db_engine: 서버형 프리뷰가 자동으로 띄울 DB 엔진을 사용자가 고른다. 기본 MYSQL.
-- origin: 이 DB 를 사용자가 만든 것(MANUAL)인지 프리뷰가 자동으로 마련한 것(PREVIEW_AUTO)인지.
--   FE 가 목록에서 구분해 표시하고, 사용자가 자동 DB 를 오해해 중복 생성하는 것을 막는다.
--   기존 행은 전부 사용자가 만든 것이므로 기본값 MANUAL 이 맞다.
ALTER TABLE preview_runtime_configs
    ADD COLUMN db_engine VARCHAR(20) NOT NULL DEFAULT 'MYSQL' COMMENT 'MYSQL | POSTGRESQL — 서버형 자동 DB 엔진';

ALTER TABLE provisioned_databases
    ADD COLUMN origin VARCHAR(20) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL | PREVIEW_AUTO';
