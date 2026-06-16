SET NAMES utf8mb4;

ALTER TABLE pipelines
    DROP FOREIGN KEY fk_pipelines_repository;

ALTER TABLE deployments
    DROP FOREIGN KEY fk_deployments_repository;

ALTER TABLE domains
    DROP FOREIGN KEY fk_domains_repository;

ALTER TABLE chat_sessions
    DROP FOREIGN KEY fk_chat_sessions_repository;

ALTER TABLE repositories
    DROP FOREIGN KEY fk_repositories_user;

RENAME TABLE repositories TO projects;

ALTER TABLE projects
    DROP INDEX uk_repositories_repo_id,
    DROP INDEX idx_repositories_user_id,
    CHANGE COLUMN repository_id project_id BIGINT NOT NULL AUTO_INCREMENT,
    ADD KEY idx_projects_user_id (user_id),
    ADD CONSTRAINT fk_projects_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id);

UPDATE projects
SET source_repository = repo_name
WHERE (source_repository IS NULL OR source_repository = '')
  AND repo_name IS NOT NULL
  AND repo_name <> '';

ALTER TABLE projects
    DROP COLUMN repo_id,
    DROP COLUMN repo_name,
    DROP COLUMN is_private,
    DROP COLUMN default_branch,
    DROP COLUMN repo_url;

ALTER TABLE pipelines
    DROP INDEX idx_pipelines_repository_id,
    CHANGE COLUMN repository_id project_id BIGINT NOT NULL,
    ADD KEY idx_pipelines_project_id (project_id),
    ADD CONSTRAINT fk_pipelines_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id);

ALTER TABLE deployments
    DROP INDEX idx_deployments_repository_id,
    CHANGE COLUMN repository_id project_id BIGINT NOT NULL,
    ADD KEY idx_deployments_project_id (project_id),
    ADD CONSTRAINT fk_deployments_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id);

ALTER TABLE domains
    DROP INDEX idx_domains_repository_id,
    CHANGE COLUMN repository_id project_id BIGINT NOT NULL,
    ADD KEY idx_domains_project_id (project_id),
    ADD CONSTRAINT fk_domains_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id);

ALTER TABLE chat_sessions
    DROP INDEX idx_chat_sessions_repository_id,
    CHANGE COLUMN repository_id project_id BIGINT NOT NULL,
    ADD KEY idx_chat_sessions_project_id (project_id),
    ADD CONSTRAINT fk_chat_sessions_project
        FOREIGN KEY (project_id)
        REFERENCES projects (project_id);
