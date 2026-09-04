-- 웹 전용(프론트 전용) 서버 여부. true 면 백엔드 앱 없이 프론트 nginx 컨테이너만 EC2 에 띄운다
-- (독립 프론트 EC2 호스팅). 기존 서버는 전부 백엔드가 있었으므로 기본값 FALSE 로 채워 하위호환.
-- deploy_mode 와 직교한다(웹 전용도 DOCKER 를 쓴다) — 그래서 별도 컬럼.
ALTER TABLE provisioned_servers
    ADD COLUMN web_only BOOLEAN NOT NULL DEFAULT FALSE AFTER api_path_prefix;
