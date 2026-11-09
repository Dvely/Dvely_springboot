-- 이슈 #618 후속: 에이전트 보존 스윕의 비우기 UPDATE 가 "아직 비우지 않은, 보존 기간을 넘긴 터미널 run" 만 읽게 한다.
--
-- AgentRunRetentionStore#blankTerminalPayloadsBatch 는 매시 "0 건이 나올 때까지" 배치를 반복한다. 지금까지 탈 수 있는
-- 인덱스는 (status, next_run_at)·(status, lease_until) 뿐이라, 옵티마이저가 status 범위를 고르면 터미널 run 전부를 읽었다.
--   - 비용: 합성 10만 행(1년, 보존 90일, 이번 시간에 비울 행 12개)에서 배치마다 99,909행을 읽었다(113~173ms).
--     (status, created_at) 인덱스로 보존 기간 안의 행을 빼도 75,315행이다 — 이미 비운 오래된 행이 매시 범위에 남는다.
--   - 잠금: 보조 인덱스로 읽으면 InnoDB 는 READ COMMITTED 에서도 semi-consistent read 를 하지 않는다. 그래서 범위 안의 행을
--     다른 트랜잭션이 쥐고 있으면 비울 대상이 아니어도 기다렸다(로컬 재현: 최근 DONE 행 하나를 쥐자 잠금 대기 2초로 실패).
--     계획이 클러스터 인덱스 훑기일 때만 기다리지 않아, #618 의 보장이 실행 계획에 달려 있었다.
--
-- 비운 run 에 시각을 남기고(payload_purged_at), 그 컬럼이 NULL 인 것만 범위로 읽는 인덱스를 둔다. 비우면 범위에서 빠지므로
-- 스윕이 읽는 행 = 비울 행이다(같은 데이터에서 11행, 2.7ms). 보존 기간 안의 행과 이미 비운 행은 아예 읽지 않는다.
-- 이 컬럼은 보존 스윕만 쓴다(엔티티에 매핑하지 않는다).
--
-- 배포 뒤 첫 스윕은 이미 비운 오래된 run 에도 시각을 한 번 남긴다(합성 7.5만 행에 151배치·1.3초). 그 뒤로는 그 시간에 경계를
-- 넘은 run 만 다룬다.
--
-- V61·V72 와 같이 information_schema 가드로 감싼다. 끝에 붙이는 NULL 허용 컬럼 추가는 INSTANT, 보조 인덱스 추가는 InnoDB
-- 온라인 DDL(INPLACE, 동시 DML 허용)이다.

SET NAMES utf8mb4;

SET @s = (SELECT IF(COUNT(*) = 0,
        'ALTER TABLE agent_runs ADD COLUMN payload_purged_at DATETIME NULL',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'agent_runs'
      AND column_name = 'payload_purged_at');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_agent_runs_retention ON agent_runs (status, payload_purged_at, created_at)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'agent_runs'
      AND index_name = 'idx_agent_runs_retention');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
