-- 이슈 #618(성능·비용 진단 #614): 에이전트 보존 스윕의 이벤트 DELETE 가 탈 인덱스.
--
-- AgentRunRetentionStore#deleteTerminalEventsBatch 는 매시 "0 건이 나올 때까지" 배치를 반복한다. 조건이
-- task_id 서브쿼리뿐이라 agent_run_events 를 매 배치 전체 스캔했다(type=ALL + DEPENDENT SUBQUERY).
-- 실측(런 10만·이벤트 60만): 17배치가 568만 행을 검사해 7,608행을 지웠고, 마지막 0 건 배치도 끝까지 훑었다.
-- 쿼리에 `created_at < :cutoff` 를 더하고 이 인덱스로 범위만 읽게 한다 — 스윕 비용이 지울 행 수에 비례한다.
--
-- V61 과 같이 information_schema 가드로 감싼다(MySQL 은 CREATE INDEX IF NOT EXISTS 가 없다).
-- 보조 인덱스 추가는 InnoDB 온라인 DDL(INPLACE, 동시 DML 허용)이다.

SET NAMES utf8mb4;

SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_agent_run_events_created ON agent_run_events (created_at)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'agent_run_events'
      AND index_name = 'idx_agent_run_events_created');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
