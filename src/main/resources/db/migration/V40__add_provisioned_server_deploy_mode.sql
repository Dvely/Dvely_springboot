-- 백엔드를 어떤 형태로 실행할지(NATIVE=인스턴스에서 java -jar / DOCKER=인스턴스에서 docker run).
-- 다스택 지원의 축 — DOCKER 는 Dockerfile 만 있으면 스택 무관하게 같은 경로로 배포된다. 기존 서버는
-- 전부 jar 방식이었으므로 기본값 NATIVE 로 채워 하위호환을 지킨다.
ALTER TABLE provisioned_servers
    ADD COLUMN deploy_mode VARCHAR(20) NOT NULL DEFAULT 'NATIVE' AFTER instance_id;
