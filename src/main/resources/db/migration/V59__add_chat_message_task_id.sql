-- 메시지를 그것을 만든 에이전트 태스크에 잇는다.
--
-- 여태 taskId 는 POST /conversations/{id}/messages 응답에만 실려 나갔다 — 방금 제출한 태스크
-- id 를 메모리에서 얹어 주는 것이라, 목록 조회(GET)에서는 전부 null 이었다. 그래서 화면은
-- "이 줄이 어느 작업의 것인가" 를 알 수 없고, 결과 줄에서 그 작업의 diff 로 넘어가거나 실패
-- 줄에서 그 작업만 재시도하는 것이 불가능했다. FE 는 "가장 마지막 것" 휴리스틱으로 우회 중이다.
--
-- NULL 허용이다: 이 칼럼 이전 행과, 태스크와 무관한 줄이 있다.
ALTER TABLE chat_messages
    ADD COLUMN task_id VARCHAR(64) NULL AFTER kind;

-- 대화의 특정 태스크 줄만 뽑는 조회를 위한 인덱스. 대화 단위로 읽고 태스크로 거르는 패턴이다.
CREATE INDEX idx_chat_messages_task ON chat_messages (chat_session_id, task_id);
