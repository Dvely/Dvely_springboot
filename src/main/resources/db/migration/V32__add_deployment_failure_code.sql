-- 배포 실패 사유의 분류. errorMessage 는 사람이 읽을 상세이고 문구가 상황마다 달라서,
-- 화면이 그 문자열을 파싱해 분기하지 않도록 분류를 값으로 따로 준다.
--
-- 특히 RESULT_UNKNOWN 은 "실패했다"가 아니라 "결과를 확인하지 못했다"이다. 웹훅을 놓친 이력을
-- 회수하려던 워커가 GitHub 에서 실행을 찾지 못한 경우이고, 사이트는 실제로 떠 있을 수 있다.
-- 이 둘이 화면에서 같은 문구로 보이면 안 된다.
--
-- 기존 행은 NULL 로 남는다. 분류를 붙이기 전에 실패한 것들이라 되살릴 근거가 없다.
ALTER TABLE deployment_histories
    ADD COLUMN failure_code VARCHAR(40) NULL AFTER error_message;
