-- 게이트웨이 프록시 타깃을 호스트 포트 → 컨테이너 IP 로 옮긴다 (#358).
--
-- host_port 를 드롭하지 않는 이유는 둘이다.
--   1) 배포 순간에 살아 있던 세션의 행이 남아 있고, 그 컨테이너는 아직 포트를 발행한 상태다.
--   2) 이 컬럼을 읽던 코드가 남아 있는 인스턴스가 롤링 중 함께 돌 수 있다.
-- 대신 NOT NULL 만 푼다 — 새 세션은 포트를 발행하지 않아 넣을 값이 없다.
--
-- container_ip 가 없는 기존 행은 게이트웨이가 첫 요청에서 컨테이너를 조회해 채운다(지연 해석).
-- IPv6 까지 담을 수 있게 45자로 둔다.
ALTER TABLE preview_sessions
    ADD COLUMN container_ip VARCHAR(45) NULL AFTER host_port,
    MODIFY COLUMN host_port INT NULL;
