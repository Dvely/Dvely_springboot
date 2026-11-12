-- 이슈 #652: 내 기기 프리뷰 공유 링크.
--
-- 내 기기에서 도는 앱 화면(1단계: dev 브라우저)을 웹 링크로 공유한다. 링크마다 <토큰>.share.<도메인> 오리진을 쓰고, 서버는
-- 보기 토큰과 "내 제어 링크" 비밀의 SHA-256 만 둔다. 평문은 생성 응답에만 실린다.
-- 연결·보는 사람·조작권은 메모리에만 있다(서버 한 대 전제). 설계: docs/device-preview-share-design.md
--
-- 번호는 머지 직전에 develop 최신 번호를 보고 다시 정한다(AGENTS.md 함정 1).

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS device_share_links (
    share_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL COMMENT 'WEB | ANDROID | IOS',
    target_label VARCHAR(255) NOT NULL COMMENT '표시용. 예: http://localhost:5173',
    token_hash CHAR(64) NOT NULL COMMENT '보기 토큰(공유 오리진의 하위 도메인 라벨) SHA-256 hex',
    control_secret_hash CHAR(64) NOT NULL COMMENT '내 제어 링크 비밀 SHA-256 hex',
    token_prefix VARCHAR(8) NOT NULL COMMENT '목록 표시용 앞 6자',
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    last_viewed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (share_id),
    UNIQUE KEY uk_device_share_links_token (token_hash),
    KEY idx_device_share_links_user (user_id, created_at),
    CONSTRAINT fk_device_share_links_user
        FOREIGN KEY (user_id)
        REFERENCES users (user_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='내 기기 프리뷰 공유 링크. 토큰·비밀은 해시만 저장한다';
