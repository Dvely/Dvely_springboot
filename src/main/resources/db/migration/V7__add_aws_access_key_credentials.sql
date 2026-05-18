SET NAMES utf8mb4;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE cloud_connections ADD COLUMN aws_credential_type VARCHAR(30) NULL COMMENT ''ACCESS_KEY | ROLE_ARN'' AFTER role_arn',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'cloud_connections'
      AND column_name = 'aws_credential_type'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE cloud_connections ADD COLUMN access_key_id VARCHAR(80) NULL COMMENT ''AWS access key ID'' AFTER aws_credential_type',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'cloud_connections'
      AND column_name = 'access_key_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE cloud_connections ADD COLUMN secret_access_key TEXT NULL COMMENT ''AES encrypted AWS secret access key'' AFTER access_key_id',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'cloud_connections'
      AND column_name = 'secret_access_key'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE cloud_connections ADD COLUMN session_token TEXT NULL COMMENT ''AES encrypted AWS session token'' AFTER secret_access_key',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'cloud_connections'
      AND column_name = 'session_token'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
