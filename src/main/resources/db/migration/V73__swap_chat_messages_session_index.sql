-- 이슈 #625 — chat_messages 의 (chat_session_id, task_id) 인덱스를 (chat_session_id, message_id) 로 교체한다.
--
-- 대화 메시지는 언제나 "한 대화를 쓴 순서(message_id)대로" 읽는다 — 목록 조회, 커서 페이지, LLM 대화 창. V61 이 단일
-- (chat_session_id) 인덱스를 (chat_session_id, task_id) 의 prefix 라며 지운 뒤로, 남은 인덱스는 task_id 순서라 그 정렬을
-- 주지 못한다. 그래서 대화를 읽을 때마다 filesort 를 탔다.
--
-- 더하기만 하지 않고 교체하는 이유(EXPLAIN ANALYZE 실측, 22,500행·대화 2,500행):
--   - 둘 다 있으면 옵티마이저가 여전히 (chat_session_id, task_id) 를 골라 정렬한다 — 새 인덱스를 쓰지 않는다
--   - (chat_session_id, task_id) 로 거르는 쿼리는 없다. V59 가 "대화의 특정 태스크 줄" 조회를 위해 만들었지만 그 조회는
--     들어오지 않았고, 지금 이 인덱스가 하는 일은 FK(fk_chat_messages_session) 를 받치는 것뿐이다. 새 인덱스도
--     chat_session_id 가 맨 앞이라 같은 일을 한다. 메시지마다 INSERT 가 도는 테이블이라 인덱스 하나를 덜 쓴다
--
-- 순서가 중요하다: 새 인덱스를 먼저 만들고 옛 것을 지운다. 반대면 FK 를 받칠 인덱스가 비는 순간 MySQL 이 DROP 을 거부한다.
-- 가드는 V61 과 같다 — DROP INDEX IF EXISTS 가 없고 DDL 은 트랜잭션이 아니라, 중간에 실패해도 반쯤 바뀐 채 남지 않게
-- 각 문장을 "있으면/없으면" 으로 감싼다.

SET NAMES utf8mb4;

SET @s = (SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_chat_messages_session_message ON chat_messages (chat_session_id, message_id)',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'chat_messages'
      AND index_name = 'idx_chat_messages_session_message');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = (SELECT IF(COUNT(*) > 0,
        'ALTER TABLE chat_messages DROP INDEX idx_chat_messages_task',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'chat_messages'
      AND index_name = 'idx_chat_messages_task');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
