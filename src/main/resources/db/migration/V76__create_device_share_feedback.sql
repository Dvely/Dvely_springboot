-- 이슈 #681: 짚기 피드백. 공유한 화면의 한 지점을 짚어 남긴 피드백과 그 순간의 이미지·이어진 대화.
--
-- 손님 글은 주인이 받아야(ACCEPTED) 에이전트로 나간다. 상태와 흐름은 docs/device-preview-share-design.md §14.
-- 세 테이블 모두 device_share_links → users CASCADE 로 탈퇴 때 함께 지워진다. 링크가 끝나고 14일 뒤 지운다.
--
-- 번호는 머지 직전에 develop 최신 번호를 보고 다시 정한다(AGENTS.md 함정 1).

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS device_share_feedback (
    feedback_id BIGINT NOT NULL AUTO_INCREMENT,
    share_id BIGINT NOT NULL,
    author_role VARCHAR(8) NOT NULL COMMENT 'OWNER(내 제어 링크) | GUEST',
    author_name VARCHAR(20) NOT NULL COMMENT '뷰어 이름',
    author_key_hash CHAR(64) NOT NULL COMMENT '남긴 사람에게만 준 확인 키의 SHA-256 hex',
    kind VARCHAR(16) NOT NULL COMMENT '링크 종류 WEB | ANDROID | IOS',
    screen_label VARCHAR(512) NOT NULL DEFAULT '' COMMENT '페이지 경로 · 패키지/Activity · 번들 ID',
    element_json TEXT NOT NULL COMMENT '서버가 다시 만든 요소 기술(설계 §14.3, 16KiB 이하)',
    body VARCHAR(1000) NOT NULL COMMENT '처음 글',
    status VARCHAR(16) NOT NULL COMMENT 'NEW | ACCEPTED | DISMISSED | FIXED | CONFIRMED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (feedback_id),
    KEY idx_device_share_feedback_share (share_id, created_at),
    KEY idx_device_share_feedback_status (status, updated_at),
    CONSTRAINT fk_device_share_feedback_share
        FOREIGN KEY (share_id)
        REFERENCES device_share_links (share_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='짚기 피드백(#681). 손님 글은 주인이 받기 전에는 PAT 로 읽을 수 없다';

CREATE TABLE IF NOT EXISTS device_share_feedback_images (
    feedback_id BIGINT NOT NULL,
    crop MEDIUMBLOB NULL COMMENT '짚은 요소를 잘라 낸 JPEG(512KiB 이하)',
    screen MEDIUMBLOB NULL COMMENT '짚은 순간의 화면 JPEG(1MiB 이하)',
    PRIMARY KEY (feedback_id),
    CONSTRAINT fk_device_share_feedback_images_feedback
        FOREIGN KEY (feedback_id)
        REFERENCES device_share_feedback (feedback_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='짚기 피드백 이미지. 목록이 읽지 않도록 나눴다';

CREATE TABLE IF NOT EXISTS device_share_feedback_notes (
    note_id BIGINT NOT NULL AUTO_INCREMENT,
    feedback_id BIGINT NOT NULL,
    author_role VARCHAR(8) NOT NULL COMMENT 'OWNER | GUEST | AGENT',
    kind VARCHAR(16) NOT NULL COMMENT 'FIXED | REOPEN | CONFIRM',
    body VARCHAR(1000) NOT NULL DEFAULT '',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (note_id),
    KEY idx_device_share_feedback_notes_feedback (feedback_id, note_id),
    CONSTRAINT fk_device_share_feedback_notes_feedback
        FOREIGN KEY (feedback_id)
        REFERENCES device_share_feedback (feedback_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='짚기 피드백에 이어진 대화(고침 메모, 다시 열기, 확인)';
