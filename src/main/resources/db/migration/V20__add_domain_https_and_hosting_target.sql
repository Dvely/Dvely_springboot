SET NAMES utf8mb4;

ALTER TABLE domains
    ADD COLUMN hosting_target VARCHAR(30) NOT NULL DEFAULT 'GITHUB_PAGES' AFTER domain_type,
    ADD COLUMN certificate_status VARCHAR(30) NOT NULL DEFAULT 'PENDING' AFTER https_enforced,
    ADD COLUMN certificate_expires_at DATE NULL AFTER certificate_status,
    MODIFY COLUMN https_enforced TINYINT(1) NOT NULL DEFAULT 0 COMMENT '실제 HTTPS 강제 적용 여부';

UPDATE domains
SET https_enforced = 0,
    certificate_status = 'PENDING',
    certificate_expires_at = NULL;
