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
