-- 자동 재시작(자가치유)을 시도했는데도 앱이 계속 무응답일 때 "복구 실패(개입 필요)" 감사 이벤트를
-- 에피소드당 1회만 남기기 위한 표시. 값이 있으면 이번 무응답 에피소드의 실패를 이미 보고함. 앱이
-- 회복되면(healthy=true) recovery_attempted_at 과 함께 NULL 로 지워져 다음 에피소드에 다시 보고할 수 있다.
ALTER TABLE provisioned_servers
    ADD COLUMN recovery_outcome_reported_at DATETIME NULL AFTER recovery_attempted_at;
