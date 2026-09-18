-- U8 8-1: LLM 토큰 사용량 계측.
--
-- 이 테이블이 생기기 전까지 응답의 usage 를 읽는 코드가 한 줄도 없었다 — 토큰을 얼마나 쓰는지
-- 아무도 몰랐고, 프롬프트 캐싱·대화 윈도우 같은 절감 작업의 효과를 잴 수단도, 태스크당 예산
-- 상한이 셀 대상도 없었다.
--
-- agent_runs 에 누적 컬럼을 더하지 않고 별도 테이블로 둔 이유:
--   1. 모든 LLM 호출이 agent_run 에 속하지 않는다. 배포 실패 분석은 태스크 없이 돈다.
--   2. agent_runs 는 보존 정책으로 삭제된다(AgentRunRetentionScheduler). 비용 이력이 함께
--      사라지면 "지난달 대비" 같은 질문에 답할 수 없다.
--   3. 호출 한 건 단위라야 라운드별 캐시 적중을 볼 수 있다. 누적 컬럼은 "캐싱이 실제로
--      살아 있는가" 에 답하지 못한다.
--   4. CODE 루프는 라운드마다 쓴다. 누적 컬럼이면 실행 중인 태스크 행을 40 번 UPDATE 하게
--      되는데, 그 행은 워커도 쓰는 뜨거운 행이다.
--
-- FK 를 걸지 않는다. 계측 행이 agent_runs 의 잠금 그래프에 끌려 들어가면 안 되고, 태스크 행이
-- 생기기 전이나 지워진 뒤에도 기록이 남아야 한다(위 2번).

CREATE TABLE llm_usage (
    llm_usage_id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(64) NULL COMMENT '귀속된 에이전트 태스크. 스코프 밖 호출은 NULL',
    user_id BIGINT NULL,
    project_id BIGINT NULL,
    phase VARCHAR(30) NOT NULL COMMENT 'DECISION / AGENT_RUN / DEPLOY_FAILURE_ANALYSIS / UNSCOPED',
    provider VARCHAR(30) NOT NULL,
    model VARCHAR(120) NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '캐시 읽기/쓰기를 제외한 입력',
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cache_creation_input_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_input_tokens BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (llm_usage_id),
    KEY idx_llm_usage_task (task_id),
    KEY idx_llm_usage_created (created_at),
    KEY idx_llm_usage_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
