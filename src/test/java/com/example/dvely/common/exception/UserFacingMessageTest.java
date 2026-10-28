package com.example.dvely.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserFacingMessageTest {

    @Test
    void dropsInternalIdAndStatusTails() {
        assertThat(UserFacingMessage.of("종료된 프리뷰입니다. 프리뷰를 다시 띄워 주세요. status=EXPIRED"))
                .isEqualTo("종료된 프리뷰입니다. 프리뷰를 다시 띄워 주세요.");
        assertThat(UserFacingMessage.of("승인 완료 처리를 진행할 수 없는 상태입니다. taskId=a1b2 status=FAILED"))
                .isEqualTo("승인 완료 처리를 진행할 수 없는 상태입니다.");
        assertThat(UserFacingMessage.of("프로젝트를 찾을 수 없습니다. projectId=42, ownerUserId=7"))
                .isEqualTo("프로젝트를 찾을 수 없습니다.");
        assertThat(UserFacingMessage.of("브랜치 비교 실패 (status=403)")).isEqualTo("브랜치 비교 실패");
        assertThat(UserFacingMessage.of("데이터베이스를 찾을 수 없거나 접근 권한이 없습니다(클라우드 연결 없음). id=3"))
                .isEqualTo("데이터베이스를 찾을 수 없거나 접근 권한이 없습니다(클라우드 연결 없음).");
    }

    @Test
    void keepsWhatTheUserNeedsAndWhatIsNotATail() {
        // 목록에 없는 키(사용자에게 의미 있는 값·실패 진단)와 문장 가운데의 값은 그대로다.
        assertThat(UserFacingMessage.of("이미 있는 환경변수입니다. key=API_URL"))
                .isEqualTo("이미 있는 환경변수입니다. key=API_URL");
        assertThat(UserFacingMessage.of("템플릿을 내려받지 못했습니다: blog (exit=128)"))
                .isEqualTo("템플릿을 내려받지 못했습니다: blog (exit=128)");
        assertThat(UserFacingMessage.of("프로젝트(id=42)에 GitHub 저장소가 연결되어 있지 않습니다."))
                .isEqualTo("프로젝트(id=42)에 GitHub 저장소가 연결되어 있지 않습니다.");
        assertThat(UserFacingMessage.of("이미 처리된 승인입니다.")).isEqualTo("이미 처리된 승인입니다.");
        assertThat(UserFacingMessage.of(null)).isNull();
    }

    @Test
    void neverReturnsAnEmptyMessage() {
        assertThat(UserFacingMessage.of("status=FAILED")).isEqualTo("status=FAILED");
    }
}
