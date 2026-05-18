SET NAMES utf8mb4;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE cloud_connections ADD COLUMN gcp_credential_type VARCHAR(40) NULL COMMENT ''SERVICE_ACCOUNT_KEY | SERVICE_ACCOUNT_EMAIL'' AFTER session_token',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'cloud_connections'
      AND column_name = 'gcp_credential_type'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(COUNT(*) = 0,
              'ALTER TABLE cloud_connections ADD COLUMN service_account_key_json TEXT NULL COMMENT ''AES encrypted GCP service account key JSON'' AFTER gcp_credential_type',
              'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'cloud_connections'
      AND column_name = 'service_account_key_json'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
