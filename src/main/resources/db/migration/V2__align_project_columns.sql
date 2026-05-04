SET NAMES utf8mb4;

ALTER TABLE repositories
    ADD COLUMN IF NOT EXISTS project_name VARCHAR(255) NOT NULL DEFAULT 'untitled-project' AFTER user_id,
    ADD COLUMN IF NOT EXISTS project_status VARCHAR(50) NOT NULL DEFAULT 'DRAFT' AFTER project_name,
    ADD COLUMN IF NOT EXISTS start_mode VARCHAR(50) NOT NULL DEFAULT 'blank' AFTER project_status,
    ADD COLUMN IF NOT EXISTS template_type VARCHAR(100) NULL AFTER start_mode,
    ADD COLUMN IF NOT EXISTS draft_mode VARCHAR(50) NOT NULL DEFAULT 'fast' AFTER template_type,
    ADD COLUMN IF NOT EXISTS deploy_status VARCHAR(50) NOT NULL DEFAULT 'DRAFT' AFTER draft_mode,
    ADD COLUMN IF NOT EXISTS current_url VARCHAR(512) NULL AFTER deploy_status,
    ADD COLUMN IF NOT EXISTS current_version VARCHAR(100) NULL AFTER current_url,
    ADD COLUMN IF NOT EXISTS source_repository VARCHAR(255) NULL AFTER current_version,
    ADD COLUMN IF NOT EXISTS deployment_repository VARCHAR(255) NULL AFTER source_repository,
    ADD COLUMN IF NOT EXISTS repository_visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE' AFTER deployment_repository,
    ADD COLUMN IF NOT EXISTS binding_status VARCHAR(30) NOT NULL DEFAULT 'NOT_BOUND' AFTER repository_visibility,
    ADD COLUMN IF NOT EXISTS repository_health VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN_ERROR' AFTER binding_status,
    ADD COLUMN IF NOT EXISTS is_deleted TINYINT(1) NOT NULL DEFAULT 0 AFTER repository_health;

UPDATE repositories
SET project_name = CASE
        WHEN project_name IS NULL OR project_name = '' THEN repo_name
        ELSE project_name
    END,
    repository_visibility = CASE
        WHEN repository_visibility IS NULL OR repository_visibility = ''
            THEN CASE WHEN is_private = 1 THEN 'PRIVATE' ELSE 'PUBLIC' END
        ELSE repository_visibility
    END,
    binding_status = CASE
        WHEN binding_status IS NULL OR binding_status = ''
            THEN CASE WHEN repo_name IS NULL OR repo_name = '' THEN 'NOT_BOUND' ELSE 'BOUND' END
        ELSE binding_status
    END,
    repository_health = CASE
        WHEN repository_health IS NULL OR repository_health = '' THEN 'UNKNOWN_ERROR'
        ELSE repository_health
    END;
