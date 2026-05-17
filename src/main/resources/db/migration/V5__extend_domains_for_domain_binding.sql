SET NAMES utf8mb4;

ALTER TABLE domains
    ADD COLUMN domain_type VARCHAR(30) NOT NULL DEFAULT 'CUSTOM_DOMAIN' AFTER domain_name,
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'VERIFYING' AFTER domain_type,
    ADD COLUMN verification_method VARCHAR(10) NULL AFTER status,
    ADD COLUMN dns_target VARCHAR(512) NULL AFTER verification_method,
    ADD COLUMN cloudflare_record_id VARCHAR(100) NULL AFTER dns_target,
    ADD COLUMN last_checked_at DATETIME NULL AFTER cloudflare_record_id;

UPDATE domains
SET domain_type = CASE
        WHEN domain_type IS NULL OR domain_type = '' THEN 'CUSTOM_DOMAIN'
        ELSE domain_type
    END,
    status = CASE
        WHEN dns_verified = 1 THEN 'CONNECTED'
        WHEN status IS NULL OR status = '' THEN 'VERIFYING'
        ELSE status
    END,
    verification_method = CASE
        WHEN verification_method IS NULL OR verification_method = '' THEN 'CNAME'
        ELSE verification_method
    END;
