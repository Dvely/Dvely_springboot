-- CLARIFY 되묻기의 구조화 질문(inputType·options) JSON. 단순 텍스트 입력(DEPLOY/DOMAIN_BIND)이면 NULL.
-- FE 가 이 값으로 라디오/체크박스/텍스트 컨트롤을 렌더한다(TaskStatusResponse.clarification 로 노출).
ALTER TABLE agent_runs
    ADD COLUMN clarification_json TEXT NULL AFTER question;
