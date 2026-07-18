SET NAMES utf8mb4;

-- Issue #56: 결과 승인 2단계 게이트
ALTER TABLE project_approval_policies
    ADD COLUMN result_approval_required TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '실행 결과(preview·diff) 확인 후 main 반영(RESULT) 승인 필요 여부';

ALTER TABLE project_changes
    ADD COLUMN approval_id BIGINT NULL
        COMMENT '결과 승인(RESULT) 행. 게이트 미발동·legacy 변경은 NULL',
    ADD COLUMN pr_number INT NULL COMMENT 'preview→main 반영 PR 번호 (멱등 no-op merge는 NULL)',
    ADD COLUMN merge_commit_sha VARCHAR(64) NULL COMMENT 'main 반영 커밋 SHA',
    ADD COLUMN merged_at DATETIME NULL COMMENT 'main 반영 시각',
    ADD KEY idx_project_changes_approval (approval_id),
    ADD CONSTRAINT fk_project_changes_approval
        FOREIGN KEY (approval_id) REFERENCES approvals (approval_id)
        ON DELETE SET NULL;
