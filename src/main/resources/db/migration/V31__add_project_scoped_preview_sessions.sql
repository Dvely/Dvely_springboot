SET NAMES utf8mb4;

-- 프로젝트 단위 프리뷰(작업 지시 없이 "현재 상태"를 그대로 띄우는 세션)를 위한 스키마 확장.
--
-- 기존 preview_sessions 행은 전부 Agent CODE 스텝이 만들었기 때문에 task_id 가 항상 존재했다
-- (NOT NULL + agent_runs FK). 프로젝트 진입/버튼으로 띄우는 세션은 그 작업 자체가 없으므로
-- task_id 를 채울 값이 없다 — NULL 을 허용하고, FK 는 그대로 둔다(값이 있으면 여전히 실재하는
-- agent_runs 행이어야 한다). task_id IS NULL 이 곧 "프로젝트 단위 세션"의 식별자다.
ALTER TABLE preview_sessions
    MODIFY COLUMN task_id VARCHAR(64) NULL;

-- 프로비저닝(clone → npm install → build → serve)은 수 분이 걸리는 비동기 작업이라 실패로 끝날 수
-- 있고, 실패한 컨테이너는 곧바로 제거되어 /preview-sessions/{id}/logs 로는 아무것도 볼 수 없다.
-- 그래서 실패 사유(+빌드 로그 꼬리)를 세션 행에 남긴다. 성공 경로에서는 항상 NULL 이다.
ALTER TABLE preview_sessions
    ADD COLUMN failure_reason VARCHAR(500) NULL AFTER status;

-- 프로젝트 진입 때마다 "이 프로젝트의 현재 세션"을 조회하는 경로(project_id + user_id + status,
-- last_accessed_at 내림차순)를 위한 인덱스. 기존 idx_preview_sessions_owner_status 는 user_id 가
-- 선두라 프로젝트 단위 조회에서는 같은 유저의 다른 프로젝트 세션까지 훑게 된다.
ALTER TABLE preview_sessions
    ADD KEY idx_preview_sessions_project_owner_status (project_id, user_id, status, last_accessed_at);
