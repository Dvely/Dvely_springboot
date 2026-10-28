-- 결과 승인 반영 범위와 거절 옵션(#532).
--
-- approvals.reject_option: RESULT 승인을 거절할 때 고른 옵션(KEEP | REVERT). 그 외 승인·결정은 비어 있다.
-- project_changes.decided_scope: 결정할 때 계산한 반영 범위(JSON). 결정 뒤에는 다시 계산하지 않고 이것을 보여 준다.
--                                계산에 실패했으면 비어 있다.

ALTER TABLE approvals
    ADD COLUMN reject_option VARCHAR(10) NULL;

ALTER TABLE project_changes
    ADD COLUMN decided_scope MEDIUMTEXT NULL;
