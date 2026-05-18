SET NAMES utf8mb4;

SET @ddl = (
    SELECT IF(COUNT(*) = 1,
              'ALTER TABLE repositories MODIFY COLUMN repo_id VARCHAR(255) NULL COMMENT ''GitHub 레포지토리 고유 ID''',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'repositories'
      AND column_name = 'repo_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 1,
              'ALTER TABLE repositories MODIFY COLUMN repo_name VARCHAR(255) NULL COMMENT ''레포지토리 이름''',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'repositories'
      AND column_name = 'repo_name'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 1,
              'ALTER TABLE repositories MODIFY COLUMN is_private TINYINT(1) NULL COMMENT ''레포지토리 공개 여부 (0: public, 1: private)''',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'repositories'
      AND column_name = 'is_private'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 1,
              'ALTER TABLE repositories MODIFY COLUMN default_branch VARCHAR(100) NULL DEFAULT NULL COMMENT ''기본 브랜치 (예: main)''',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'repositories'
      AND column_name = 'default_branch'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 1,
              'ALTER TABLE repositories MODIFY COLUMN repo_url VARCHAR(512) NULL COMMENT ''GitHub 레포지토리 URL''',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'repositories'
      AND column_name = 'repo_url'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
