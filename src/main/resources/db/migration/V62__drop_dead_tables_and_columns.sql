-- 이슈 #338 (3-2) 죽은 구조 제거.
--
-- 인덱스 정리(V61)와 **일부러 파일을 나눴다.** 인덱스는 틀리면 다시 만들면 되지만 여기 있는 것들은
-- 되돌릴 수 없다 — 리뷰가 분리돼야 한다.
--
-- ⚠️ 머지 전에 두 환경(dev·운영)에서 아래를 직접 확인할 것. 작업자는 dev/운영 DB 에 접근할 수
--    없어 로컬 테스트 스키마에서만 확인했다(그쪽은 항상 0 행이라 증거가 되지 않는다).
--
--    SELECT (SELECT COUNT(*) FROM pipelines)   AS pipelines,
--           (SELECT COUNT(*) FROM deployments) AS deployments,
--           (SELECT COUNT(*) FROM users WHERE access_token     IS NOT NULL) AS access_token,
--           (SELECT COUNT(*) FROM users WHERE scope            IS NOT NULL) AS scope,
--           (SELECT COUNT(*) FROM users WHERE token_expires_at IS NOT NULL) AS token_expires_at;
--
--    pipelines·deployments 가 0 이 아니면 이 마이그레이션을 **멈추고** 그 행이 무엇인지 먼저
--    확인한다. users 쪽 3 컬럼은 0 이 아니어도 진행한다 — 아래 사유 참조.

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 0. 안전 게이트 — 데이터가 있으면 지우지 않고 **멈춘다**.
--
-- 위의 "머지 전에 두 환경에서 확인할 것" 은 사람이 잊을 수 있고, 잊은 결과가 되돌릴 수 없는
-- 삭제다. 그래서 그 확인을 사람의 절차가 아니라 마이그레이션 자신의 선행 조건으로 만든다.
--
-- pipelines·deployments 에 한 행이라도 있으면 여기서 실패한다. 이 블록이 모든 DROP 보다 앞에
-- 있으므로 그때 아래 문장은 **하나도 실행되지 않는다.** Flyway 가 멈추고 배포가 실패한다 —
-- 아무도 안 쓴다고 믿었던 행을 조용히 잃는 것보다 시끄럽게 실패하는 편이 낫다.
--
-- 실패했다면: 그 행이 무엇인지 먼저 본다. 버려도 되는 것이면 해당 환경에서 비우고 다시 배포하고,
-- 살릴 값이면 이 마이그레이션을 되돌린 뒤 옮길 곳을 정한다.
--
-- users 3 컬럼은 이 게이트에 넣지 않는다 — 값이 있어도 지우는 것이 맞기 때문이다(2번 참조).
--
-- 테이블이 이미 없는 환경(예: 신규 스키마)에서도 돌아야 하므로, COUNT 문장 자체를 존재 여부로
-- 감싼다. 없으면 0 으로 두고 통과한다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) > 0,
        'SELECT COUNT(*) INTO @cnt_pipelines FROM pipelines',
        'SET @cnt_pipelines = 0')
    FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'pipelines');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = (SELECT IF(COUNT(*) > 0,
        'SELECT COUNT(*) INTO @cnt_deployments FROM deployments',
        'SET @cnt_deployments = 0')
    FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'deployments');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 조건을 만족하면 존재하지 않는 테이블을 참조해 실패시킨다.
--
-- SIGNAL 을 쓰고 싶지만 준비문 안에서는 지원되지 않는다(실측: ERROR 1295 "This command is not
-- supported in the prepared statement protocol yet"). 그것도 실패는 시키지만 오류 메시지가
-- 원인을 전혀 설명하지 못해, 다음 사람이 이 파일을 열어보기 전까지는 무슨 일인지 알 수 없다.
--
-- 그래서 테이블 이름 자체에 사유와 행 수를 담는다. Flyway 실패 로그에 그대로 찍힌다:
--   Table 'dvely.V62_ABORT_pipelines_1_deployments_0_rows_exist' doesn't exist
-- 식별자 상한이 64자라 ASCII 로 짧게 유지한다.
SET @s = (SELECT IF(@cnt_pipelines + @cnt_deployments > 0,
        CONCAT('SELECT 1 FROM `V62_ABORT_pipelines_', @cnt_pipelines,
               '_deployments_', @cnt_deployments, '_rows_exist`'),
        'SELECT 1'));
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 1. pipelines · deployments — V1 에서 만들어진 뒤 한 번도 매핑되지 않은 테이블.
--
-- 근거: 저장소의 @Table 매핑 30 개 어디에도 두 이름이 없고, 두 테이블을 읽거나 쓰는 코드도 없다.
-- 배포 이력의 실제 정본은 V3 이후의 deployment_histories 다.
--
-- 남겨두는 비용이 0 이 아닌 이유: 둘 다 projects 를 FK 로 참조한다. 그래서 프로젝트를 지울
-- 때마다 InnoDB 가 아무도 안 쓰는 두 테이블의 참조 검사를 한다.
--
-- 순서 주의: deployments 가 pipelines 를 참조하므로 자식(deployments)을 먼저 지운다.
-- 테이블을 지우면 그 테이블이 갖고 있던 FK 도 함께 사라지므로 별도 해제 문장은 필요 없다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) > 0, 'DROP TABLE deployments', 'SELECT 1')
    FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'deployments');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = (SELECT IF(COUNT(*) > 0, 'DROP TABLE pipelines', 'SELECT 1')
    FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'pipelines');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2. users.access_token · scope · token_expires_at — V1 의 OAuth 시절 잔재.
--
-- UserEntity 는 이 셋 중 아무것도 매핑하지 않는다. 지금 쓰는 토큰 컬럼은 V12 가 넣은
-- github_user_access_token / github_user_refresh_token / user_access_token_expires_at 이고,
-- 그쪽은 AesEncryptor 로 암호화해 저장한다.
--
-- 값이 남아 있어도 지우는 이유: access_token 은 **평문** GitHub 토큰이 들어가던 컬럼이다.
-- 아무도 읽지 않는 평문 자격증명이 DB 에 남아 있는 것 자체가 부채라, 살리는 것이 아니라 없애는
-- 것이 맞는 방향이다. 이 값을 다시 쓰려면 어차피 암호화 컬럼으로 재발급받아야 한다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) > 0, 'ALTER TABLE users DROP COLUMN access_token', 'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'access_token');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = (SELECT IF(COUNT(*) > 0, 'ALTER TABLE users DROP COLUMN scope', 'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'scope');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = (SELECT IF(COUNT(*) > 0, 'ALTER TABLE users DROP COLUMN token_expires_at', 'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'token_expires_at');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
