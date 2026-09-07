-- 되묻기에 답한 뒤에도 "무엇을 물었고 무엇을 골랐는지" 를 다시 그릴 수 있게 보존한다.
--
-- clarification_json 은 답을 받는 순간 NULL 로 지워진다(폼이 사라지게 해서 이중 제출을 막는
-- 장치라 그대로 둔다). 그 결과 대화에는 질문만 남고 답이 사라져, 사용자가 무엇을 골랐는지
-- 확인할 방법이 없었다. 지우는 쪽을 건드리면 기존 FE 가 폼을 다시 띄울 위험이 있어,
-- 답변 시점의 스냅샷을 별도 칼럼에 남긴다.
--
-- 내용은 ClarificationRequest(question/inputType/options/allowOther) 에 answer 를 더한 JSON 이다.
ALTER TABLE agent_runs
    ADD COLUMN answered_clarification_json TEXT NULL AFTER clarification_json;
