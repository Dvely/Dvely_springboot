-- 서버 측 git 작업(#523 PR-2): 변경 내역을 만든 스냅샷 트리와, 반영을 승인 뒤로 미뤘는지를 남긴다.
--
-- snapshot_tree: 서버가 컨테이너 작업 트리로 만든 git 트리 id(40자). 결과 승인 뒤 반영할 때 다시 뜬 트리가
--                이것과 같을 때만 push 한다. 플래그가 꺼져 있으면 비어 있다.
-- push_deferred: .github 아래 변경처럼 승인 없이 저장소에 올리지 않는 변경이라 push 를 승인 뒤로 미뤘는지.

ALTER TABLE project_changes
    ADD COLUMN snapshot_tree VARCHAR(40) NULL,
    ADD COLUMN push_deferred TINYINT(1) NOT NULL DEFAULT 0;
