SET NAMES utf8mb4;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE chat_sessions ADD COLUMN title VARCHAR(120) NOT NULL DEFAULT ''새 대화'' AFTER project_id',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_sessions'
      AND column_name = 'title'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE chat_sessions ADD KEY idx_chat_sessions_trash_expiry (is_deleted, deleted_at)',
              'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_sessions'
      AND index_name = 'idx_chat_sessions_trash_expiry'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE chat_sessions
SET deleted_at = updated_at
WHERE is_deleted = 1
  AND deleted_at IS NULL;

ALTER TABLE chat_messages
    DROP FOREIGN KEY fk_chat_messages_session;

ALTER TABLE chat_messages
    ADD CONSTRAINT fk_chat_messages_session
        FOREIGN KEY (chat_session_id)
        REFERENCES chat_sessions (chat_session_id)
        ON DELETE CASCADE;

ALTER TABLE approvals
    DROP FOREIGN KEY fk_approvals_chat_session;

ALTER TABLE approvals
    ADD CONSTRAINT fk_approvals_chat_session
        FOREIGN KEY (chat_session_id)
        REFERENCES chat_sessions (chat_session_id)
        ON DELETE SET NULL;

ALTER TABLE agent_runs
    DROP FOREIGN KEY fk_agent_runs_chat_session;

ALTER TABLE agent_runs
    ADD CONSTRAINT fk_agent_runs_chat_session
        FOREIGN KEY (chat_session_id)
        REFERENCES chat_sessions (chat_session_id)
        ON DELETE SET NULL;

ALTER TABLE preview_sessions
    DROP FOREIGN KEY fk_preview_sessions_chat_session;

ALTER TABLE preview_sessions
    ADD CONSTRAINT fk_preview_sessions_chat_session
        FOREIGN KEY (chat_session_id)
        REFERENCES chat_sessions (chat_session_id)
        ON DELETE SET NULL;

ALTER TABLE project_changes
    DROP FOREIGN KEY fk_project_changes_chat_session;

ALTER TABLE project_changes
    ADD CONSTRAINT fk_project_changes_chat_session
        FOREIGN KEY (chat_session_id)
        REFERENCES chat_sessions (chat_session_id)
        ON DELETE SET NULL;
