-- 이슈 #694 PR-5: 짚기 피드백 하나에 여러 곳(①~⑤). docs/device-preview-share-design.md §15.5·§15.10.
--
-- ②부터의 짚기 결과는 element_json 의 marks 에 들어간다(결과 48KiB × 5). 짚은 곳마다 잘라 낸 그림과 번호를 그린 화면을 둔다.
-- 번호는 머지 직전에 develop 최신 번호를 보고 다시 정한다(AGENTS.md 함정 1).

SET NAMES utf8mb4;

ALTER TABLE device_share_feedback
    MODIFY element_json MEDIUMTEXT NOT NULL COMMENT '서버가 다시 만든 짚기 기술(설계 §14.3·§15.10). ②부터는 marks';

ALTER TABLE device_share_feedback_images
    ADD COLUMN annotated MEDIUMBLOB NULL COMMENT '짚은 곳 테두리와 번호를 그린 화면 JPEG(여러 곳일 때, 1MiB 이하)' AFTER screen;

CREATE TABLE IF NOT EXISTS device_share_feedback_mark_images (
    feedback_id BIGINT NOT NULL,
    mark_no INT NOT NULL COMMENT '짚은 곳 번호. 2부터(①은 device_share_feedback_images.crop)',
    crop MEDIUMBLOB NOT NULL COMMENT '그 곳에서 고른 범위를 잘라 낸 JPEG(512KiB 이하)',
    PRIMARY KEY (feedback_id, mark_no),
    CONSTRAINT fk_device_share_feedback_mark_images_feedback
        FOREIGN KEY (feedback_id)
        REFERENCES device_share_feedback (feedback_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='짚기 피드백의 ②부터 짚은 곳마다 잘라 낸 그림';
