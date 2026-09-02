-- RDS 행이 생성에 쓴 클라우드 연결을 기억한다. 상태 워커는 프로젝트의 '현재' 선택이 아니라 이 값으로
-- describe 자격을 얻어야 한다 — 생성(수 분) 도중 프로젝트의 연결이 바뀌면, 다른 계정 자격으로 조회해
-- 살아있는 인스턴스를 '없음/실패'로 오판하고(고아 과금 자원), 상태를 잘못 FAILED 로 넘기기 때문이다.
-- LOCAL/자동은 클라우드 자격이 없으므로 null 이다.
ALTER TABLE provisioned_databases
    ADD COLUMN cloud_connection_id BIGINT NULL COMMENT 'RDS 생성에 쓴 클라우드 연결. LOCAL/자동은 null';
