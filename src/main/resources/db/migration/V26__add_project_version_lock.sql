SET NAMES utf8mb4;

-- Note: MySQL requires COMMENT before the positional AFTER clause in a column definition
-- (the design draft had them swapped, which fails with ERROR 1064 — fixed here; see report).
ALTER TABLE projects
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT 'JPA @Version 낙관적 잠금 카운터' AFTER is_deleted;
