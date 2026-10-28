-- 결과 승인 반영 범위(#532) — 서버가 push 한 커밋 기록.
--
-- server_pushed_commits: 서버 측 git 이 실제로 push 한 커밋. 반영 범위에서 "이번 작업의 커밋"과 "거절 REVERT 커밋"은
--                        이 기록과 sha 가 맞을 때만 그렇게 본다. 커밋 메시지는 저장소에 push 할 수 있는 누구나 쓸 수 있다.
--   kind: TASK(작업 결과 push) | REVERT(거절 REVERT 로 preview 를 main 상태로 되돌린 커밋)

CREATE TABLE server_pushed_commits (
    commit_sha  VARCHAR(40)  NOT NULL,
    repository  VARCHAR(255) NOT NULL,
    task_id     VARCHAR(64)  NULL,
    kind        VARCHAR(10)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (commit_sha, repository)
);
