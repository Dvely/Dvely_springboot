-- 이슈 #338 (3-1) 인덱스 정리. 세 종류를 한 파일에 모은다: 누락 인덱스 추가, prefix 중복 제거,
-- 그리고 보존 스윕(3-3)이 탈 인덱스 마련.
--
-- 왜 information_schema 가드를 두는가 — MySQL 은 `DROP INDEX IF EXISTS` 를 지원하지 않는다.
-- DDL 은 트랜잭션이 아니므로 중간에서 실패하면 스키마가 반쯤 바뀐 채 남는다. V19 가 쓴 것과 같은
-- PREPARE 가드로 각 문장을 "있으면/없으면"으로 감싸 그 상태를 만들지 않는다.
--
-- 주의: `*SchemaTest` 도 `ddl-auto: validate` 도 인덱스를 보지 않는다. 이 파일이 틀려도 테스트는
-- 통과한다 — 검증은 EXPLAIN 으로 했다(PR 본문에 전/후 수치).

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. projects — 모든 GitHub 웹훅이 여기서 풀스캔을 탔다.
--
-- WebhookEventHandler 의 push / pull_request / workflow_run 경로가 전부
-- findFirstBySourceRepositoryIgnoreCase / findBySourceRepositoryIgnoreCaseAndDeletedFalse 로
-- 들어온다. 인덱스가 아예 없었고, 설령 있었어도 `IgnoreCase` 가 만드는
-- `upper(source_repository) = upper(?)` 는 컬럼에 함수를 씌워 인덱스를 못 탄다.
-- source_repository 의 컬레이션은 utf8mb4_unicode_ci — 즉 `upper()` 없이도 대소문자를 이미
-- 무시한다. `IgnoreCase` 는 효과 없이 인덱스만 죽이고 있었으므로 리포지토리에서 함께 걷어낸다.
--
-- is_deleted 를 뒤에 붙이는 이유: 두 호출 중 하나가 `AndDeletedFalse` 라 인덱스만으로 걸러진다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_projects_source_repository ON projects (source_repository, is_deleted)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'projects'
      AND index_name = 'idx_projects_source_repository');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2. domains — DomainVerificationWorker 가 60초마다 status 로 훑는데 인덱스가 없었다.
-- (hostname 쪽 UK 는 이미 있다. 그걸 무시하게 만들던 `existsByHostnameIgnoreCase` 의
--  `IgnoreCase` 는 위와 같은 이유로 리포지토리에서 제거한다 — DDL 로는 할 일이 없다.)
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_domains_status_created ON domains (status, created_at)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'domains'
      AND index_name = 'idx_domains_status_created');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3. chat_messages — idx_chat_messages_chat_session_id 는 idx_chat_messages_task
--    (chat_session_id, task_id) 의 prefix 라 완전 중복이다. 메시지마다 INSERT 가 도는
--    테이블에서 같은 값을 두 인덱스에 쓰고 있었다.
--    FK fk_chat_messages_session 은 idx_chat_messages_task 가 leftmost 로 받쳐준다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) > 0,
        'ALTER TABLE chat_messages DROP INDEX idx_chat_messages_chat_session_id',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'chat_messages'
      AND index_name = 'idx_chat_messages_chat_session_id');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 4. deployment_histories — 새 복합을 먼저 만들고 나서 단일 project_id 를 지운다(순서 중요:
--    지우는 쪽이 먼저면 그 사이 project_id 를 받쳐줄 인덱스가 비는 순간이 생긴다).
--    (project_id, triggered_at, history_id) 는
--    findByProjectIdOrderByTriggeredAtDesc 와
--    findFirstByProjectIdOrderByTriggeredAtDescIdDesc 를 filesort 없이 받는다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_deployment_histories_project_triggered ON deployment_histories (project_id, triggered_at, history_id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'deployment_histories'
      AND index_name = 'idx_deployment_histories_project_triggered');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = (SELECT IF(COUNT(*) > 0,
        'ALTER TABLE deployment_histories DROP INDEX idx_deployment_histories_project_id',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'deployment_histories'
      AND index_name = 'idx_deployment_histories_project_id');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 5. revoked_access_tokens — idx_revoked_tokens_jti 는 V12 의
--    uk_revoked_access_tokens_jti 와 컬럼·순서가 같은 완전 중복이다.
--    대신 TokenCleanupScheduler 의 `expires_at <` 스윕이 탈 인덱스를 넣는다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) > 0,
        'ALTER TABLE revoked_access_tokens DROP INDEX idx_revoked_tokens_jti',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'revoked_access_tokens'
      AND index_name = 'idx_revoked_tokens_jti');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_revoked_access_tokens_expires_at ON revoked_access_tokens (expires_at)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'revoked_access_tokens'
      AND index_name = 'idx_revoked_access_tokens_expires_at');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 6. refresh_tokens — 같은 스윕의 다른 절반.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'refresh_tokens'
      AND index_name = 'idx_refresh_tokens_expires_at');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 7. cloud_connections — idx_cloud_connections_user_id 는
--    idx_cloud_connections_user_provider (user_id, provider) 의 prefix 라 중복이고,
--    idx_cloud_connections_user_status (user_id, status) 는 어떤 리포지토리 쿼리도 쓰지 않는다.
--    FK fk_cloud_connections_user 는 user_provider 가 leftmost 로 받쳐준다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) > 0,
        'ALTER TABLE cloud_connections DROP INDEX idx_cloud_connections_user_id',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'cloud_connections'
      AND index_name = 'idx_cloud_connections_user_id');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = (SELECT IF(COUNT(*) > 0,
        'ALTER TABLE cloud_connections DROP INDEX idx_cloud_connections_user_status',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'cloud_connections'
      AND index_name = 'idx_cloud_connections_user_status');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 8. webhook_deliveries — idx_webhook_deliveries_event (event_type, received_at) 는
--    리포지토리 어디서도 안 쓴다. payload LONGBLOB 이 매 이벤트마다 쌓이는 테이블이라
--    안 쓰는 인덱스의 쓰기 비용이 그대로 낭비다. (status, received_at) 으로 교체해
--    3-3 의 WebhookDeliveryRetentionScheduler 가 탈 인덱스를 준다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_webhook_deliveries_retention ON webhook_deliveries (status, received_at)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'webhook_deliveries'
      AND index_name = 'idx_webhook_deliveries_retention');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = (SELECT IF(COUNT(*) > 0,
        'ALTER TABLE webhook_deliveries DROP INDEX idx_webhook_deliveries_event',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'webhook_deliveries'
      AND index_name = 'idx_webhook_deliveries_event');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 9. users — SpringDataUserRepository.findByGithubInstallationId 가 무인덱스였다.
--
-- UNIQUE 로 거는 이유: 반환 타입이 Optional<UserEntity> 다. 같은 installation id 가 두 행에
-- 있으면 지금도 IncorrectResultSizeDataAccessException 으로 터진다 — 즉 코드가 이미 유일성을
-- 전제하고 있고, DDL 이 그 전제를 뒤늦게 적는 것뿐이다.
-- 중복이 실제로 있는 환경에서는 이 마이그레이션이 실패한다. 그것이 맞는 동작이다(런타임에
-- 조용히 500 을 내는 것보다 배포 시점에 멈추는 편이 낫다). 선행 확인 쿼리는 PR 본문에 있다.
-- NULL 은 MySQL UNIQUE 에서 중복이 허용되므로 App 미설치 사용자는 영향받지 않는다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE UNIQUE INDEX uk_users_github_installation_id ON users (github_installation_id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'users'
      AND index_name = 'uk_users_github_installation_id');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 10. approvals — existsByProjectIdAndTypeAndStatus 가 project_id 만 인덱스로 좁히고
--     나머지 두 조건은 행을 읽어 걸렀다. 복합을 만든 뒤 단일 project_id 를 지운다
--     — 새 복합의 prefix 라 그대로 두면 방금 없앤 것과 같은 종류의 중복을 새로 만드는 셈이다.
--     FK fk_approvals_project 는 새 복합이 leftmost 로 받쳐준다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_approvals_project_type_status ON approvals (project_id, approval_type, status)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'approvals'
      AND index_name = 'idx_approvals_project_type_status');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = (SELECT IF(COUNT(*) > 0,
        'ALTER TABLE approvals DROP INDEX idx_approvals_project_id',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'approvals'
      AND index_name = 'idx_approvals_project_id');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 11. chat_sessions — 목록 조회
--     findByUserIdAndProjectIdAndDeletedFalseOrderByUpdatedAtDesc 를 한 인덱스로 받는다.
--     idx_chat_sessions_user_id 는 이 복합의 prefix 가 되므로 함께 지운다(10번과 같은 이유).
--     idx_chat_sessions_project_id 는 prefix 가 아니고 FK fk_chat_sessions_project 를
--     받치고 있으므로 남긴다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_chat_sessions_user_project_list ON chat_sessions (user_id, project_id, is_deleted, updated_at)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'chat_sessions'
      AND index_name = 'idx_chat_sessions_user_project_list');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = (SELECT IF(COUNT(*) > 0,
        'ALTER TABLE chat_sessions DROP INDEX idx_chat_sessions_user_id',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'chat_sessions'
      AND index_name = 'idx_chat_sessions_user_id');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 12. provisioned_servers — countInFlightByCloudConnectionId 등이 무인덱스다.
--     DDL 만 넣는다. 이 도메인의 자바 코드는 다른 작업자 구역이라 건드리지 않는다.
-- ---------------------------------------------------------------------------
SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_provisioned_servers_connection_status ON provisioned_servers (cloud_connection_id, status)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'provisioned_servers'
      AND index_name = 'idx_provisioned_servers_connection_status');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 13. cdn_deletions — V45 의 CREATE TABLE 에 CHARSET/COLLATE 절이 없어 DB 기본값으로
--     생성됐다. MySQL 8 의 서버 기본은 utf8mb4_0900_ai_ci 라, 스키마를 명시적으로 만들지 않은
--     환경에서는 이 테이블만 다른 컬레이션을 갖는다. 다른 테이블의 VARCHAR 과 비교·조인할 때
--     "Illegal mix of collations" 로 터지는 종류의 부채라 지금 맞춰둔다.
--     이미 utf8mb4_unicode_ci 인 환경(CI·로컬 테스트 스키마)에서는 사실상 no-op 이다.
-- ---------------------------------------------------------------------------
ALTER TABLE cdn_deletions CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
