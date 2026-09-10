-- 진행 상황 이벤트에 "몇 단계 중 몇 번째의 무슨 작업인지" 를 싣는다.
--
-- 여태 이벤트는 태스크 생명주기(CREATED/QUEUED/STARTED/COMPLETED)뿐이라, 코드 생성처럼
-- 몇 분 걸리는 스텝이 도는 동안 화면에 아무 변화가 없었다. 사용자는 진행 중인지 멈춘 건지
-- 오류인지 구분할 수 없다.
--
-- 셋 다 NULL 허용이다 — 스텝과 무관한 기존 생명주기 이벤트는 값이 없다.
ALTER TABLE agent_run_events
    ADD COLUMN step_index  INT         NULL AFTER message,
    ADD COLUMN step_total  INT         NULL AFTER step_index,
    ADD COLUMN agent_type  VARCHAR(30) NULL AFTER step_total;
