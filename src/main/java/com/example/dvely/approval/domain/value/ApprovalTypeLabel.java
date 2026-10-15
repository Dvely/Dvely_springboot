package com.example.dvely.approval.domain.value;

/**
 * 채팅 기록에 남기는 승인 유형 이름.
 *
 * <p>전에는 채팅에 {@code - [48] CHANGE} 처럼 원시 enum 을 그대로 남겼다(#457). 승인 카드와 승인
 * 탭은 FE {@code approvalCopy.ts} 의 제목을 쓰므로, 이름을 그쪽 제목과 같게 둔다 — 같은 승인이
 * 채팅 기록과 카드에서 다른 이름으로 읽히지 않게 하기 위해서다.
 *
 * <p>switch 에 default 를 두지 않는다. 유형이 늘면 여기서 컴파일이 깨져야 이름 없이 원시 값이 다시
 * 새는 일이 없다.
 */
public final class ApprovalTypeLabel {

    private ApprovalTypeLabel() {
    }

    public static String of(ApprovalType type) {
        return switch (type) {
            case CHANGE -> "변경 사항 승인";
            case DEPLOYMENT -> "배포 승인";
            case DOMAIN_BINDING -> "도메인 연결 승인";
            case DOMAIN_UNBIND -> "도메인 연결 해제 승인";
            case INFRA_OPERATION -> "인프라 작업 승인";
            case RESULT -> "작업 결과 승인";
            case REPOSITORY_BINDING -> "GitHub 저장소 연결";
            case DATABASE_PROVISION -> "데이터베이스 생성";
            case SERVER_PROVISION -> "백엔드 서버 생성";
        };
    }
}
