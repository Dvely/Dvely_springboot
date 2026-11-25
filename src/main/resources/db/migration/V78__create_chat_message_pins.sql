-- 이슈 #575: 서버 프리뷰에서 짚은 곳을 대화 메시지에 붙인다. docs/device-preview-share-design.md §16.6.
--
-- 사용자 메시지 하나에 짚은 곳 5개까지(①~⑤). element_json 은 서버가 PinResultSanitizer 로 다시 만든 짚기 기술이다(48KiB 이하).
-- 메시지를 지우면(대화 영구 삭제·휴지통 정리·탈퇴의 chat_sessions CASCADE) 함께 지워진다.
-- 번호는 머지 직전에 develop 최신 번호를 보고 다시 정한다(AGENTS.md 함정 1).

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message_pins (
    message_id BIGINT NOT NULL,
    position INT NOT NULL COMMENT '짚은 곳 번호 1~5(①~⑤). 보낸 순서',
    label VARCHAR(255) NOT NULL COMMENT '칩에 보이는 이름(고른 범위의 이름)',
    element_json MEDIUMTEXT NOT NULL COMMENT '서버가 다시 만든 짚기 기술(설계 §14.3·§15). 고른 칸은 scope',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (message_id, position),
    CONSTRAINT fk_chat_message_pins_message
        FOREIGN KEY (message_id)
        REFERENCES chat_messages (message_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='서버 프리뷰에서 짚어 사용자 메시지에 붙인 곳';
