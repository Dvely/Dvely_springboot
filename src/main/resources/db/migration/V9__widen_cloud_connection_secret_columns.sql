SET NAMES utf8mb4;

ALTER TABLE cloud_connections
    MODIFY COLUMN secret_access_key TEXT NULL COMMENT 'AES encrypted AWS secret access key',
    MODIFY COLUMN session_token TEXT NULL COMMENT 'AES encrypted AWS session token',
    MODIFY COLUMN service_account_key_json TEXT NULL COMMENT 'AES encrypted GCP service account key JSON';
