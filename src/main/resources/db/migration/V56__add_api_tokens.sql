SET NAMES utf8mb4;

-- 에이전트·CLI 용 개인 액세스 토큰(PAT).
--
-- 기존 인증은 GitHub OAuth → JWT 이고 액세스 토큰이 1시간이라, 브라우저 없는 클라이언트
-- (MCP 서버·CLI·CI)가 쓸 장수명 자격이 없었다. 이 표가 그것을 채운다.
--
-- 원문이 아니라 해시만 저장한다. ai_provider_credentials 는 키를 벤더에 전달해야 해서 복호화가
-- 가능한 AES 저장이었지만, PAT 는 우리가 검증만 하면 되므로 원문을 보관할 이유가 없다.
-- 발급 시 1회만 평문을 보여주고 이후에는 재노출이 불가능하다.
--
-- 해시가 SHA-256 인 이유: 토큰은 256비트 난수라 추측 대상이 아니다. bcrypt 류의 work factor 는
-- 엔트로피가 낮은 비밀번호를 느리게 만들려는 장치이고, 여기서는 브루트포스가 애초에 불가능한
-- 대신 이 조회가 모든 API 요청마다 일어난다.
CREATE TABLE api_tokens (
    api_token_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    -- 전체 토큰의 SHA-256(hex). 제시된 토큰을 해싱해 이 컬럼으로 바로 찾는다.
    token_hash CHAR(64) NOT NULL,
    -- 목록 화면에서 "어느 토큰인지" 알아보게 하는 앞부분. 평문 재노출이 아니다.
    token_prefix VARCHAR(16) NOT NULL COMMENT '예: qp_a1b2c3d4',
    scope VARCHAR(10) NOT NULL COMMENT 'READ | WRITE',
    label VARCHAR(64) NULL COMMENT '사용자가 붙이는 이름(선택)',
    expires_at DATETIME NOT NULL,
    -- 매 요청마다 쓰지 않는다. 1시간 이상 지났을 때만 갱신한다(애플리케이션 레벨 스로틀).
    last_used_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (api_token_id),
    UNIQUE KEY uk_api_tokens_hash (token_hash),
    KEY idx_api_tokens_user (user_id),
    CONSTRAINT fk_api_tokens_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='에이전트·CLI 용 개인 액세스 토큰. 해시만 저장하며 평문은 발급 시 1회만 노출한다';
