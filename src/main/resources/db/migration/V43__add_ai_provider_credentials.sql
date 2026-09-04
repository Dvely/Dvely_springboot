SET NAMES utf8mb4;

-- 사용자 본인(BYOK) AI 제공자 API 키.
-- 키는 environment_variables.env_value 와 동일하게 AesEncryptor(AES-256-GCM) 로 at-rest 암호화한다
-- — 신규 crypto 코드 없이 기존 컨버터를 그대로 재사용(U3 방침).
--
-- provider 는 "실행 모드"가 아니라 "벤더" 단위다. Claude Code 는 ANTHROPIC 키를, Codex 는 OPENAI 키를
-- 쓰므로 실행 모드별로 행을 나누면 사용자가 같은 키를 두 번 넣어야 한다. 실행 모드(CLAUDE_CODE/CODEX)는
-- 이 표를 조회할 때 벤더로 환산한다.
--
-- user 스코프가 강제인 이유: 운영자 키·구독을 여러 사용자에게 풀링/재판매/중개하는 것은 제공사 약관
-- 위반이다(BYOK 설계 §컴플라이언스). project 스코프가 필요해지면 후속 마이그레이션에서 확장한다.
CREATE TABLE ai_provider_credentials (
    ai_provider_credential_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL COMMENT 'ANTHROPIC | OPENAI | GLM (벤더 단위)',
    encrypted_api_key MEDIUMTEXT NOT NULL COMMENT 'AES-256-GCM 암호문(Base64), 애플리케이션 레벨 암호화',
    label VARCHAR(64) NULL COMMENT '사용자가 붙이는 식별용 이름(선택)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (ai_provider_credential_id),
    UNIQUE KEY uk_ai_provider_credentials_user_provider (user_id, provider),
    CONSTRAINT fk_ai_provider_credentials_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='사용자 본인(BYOK) AI 제공자 API 키 — 운영자 풀링 금지 원칙상 user 스코프 강제';
