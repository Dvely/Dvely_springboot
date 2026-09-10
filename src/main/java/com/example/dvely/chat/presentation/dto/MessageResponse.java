package com.example.dvely.chat.presentation.dto;

import com.example.dvely.chat.domain.value.ChatMessageKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "대화 메시지 정보")
public record MessageResponse(
        @Schema(description = "메시지 ID", example = "3001")
        Long messageId,

        @Schema(description = "메시지가 속한 대화 ID", example = "101")
        Long conversationId,

        @Schema(description = "메시지 역할. 현재 메시지 생성 API는 USER만 저장합니다.", example = "user")
        String role,

        @Schema(description = "메시지 본문", example = "랜딩 페이지에 FAQ 섹션을 추가해줘")
        String content,

        @Schema(description = "메시지 토큰 수. 현재 사용자 메시지는 0으로 저장됩니다.", example = "0")
        long tokenCount,

        @Schema(description = "메시지 생성 시각")
        LocalDateTime createdAt,

        @Schema(description = """
                이 줄을 만든(또는 이 발화가 낳은) Agent 작업의 taskId. 과거 메시지 조회(GET messages)에서도
                채워진다 — 예전에는 POST 응답에만 실려 나가 목록에서는 전부 null 이었다. 이 값으로 결과 줄에서
                그 작업의 변경 내역(GET /api/v1/changes)으로 넘어가거나, 실패 줄에서 그 작업만 재시도할 수 있다.
                태스크와 무관한 줄과 이 필드 도입(V59) 이전에 쌓인 줄은 null 이다.
                """,
                example = "a1b2c3d4e5f6", nullable = true)
        String taskId,

        @Schema(description = """
                어시스턴트 줄의 종류. 화면이 본문 문자열로 의미를 추론하지 않도록 서버가 붙인다
                (예전에 본문에 "승인 후 실행" 이 있으면 승인 버튼을 붙였다가, 모델이 그 문장을 지어내
                존재하지 않는 버튼이 뜬 적이 있다).
                AGENT_RESULT: 에이전트가 만든 결과·답변 / TASK_PROGRESS: 진행·전이 안내 /
                APPROVAL_REQUESTED: 승인 필요 / INPUT_REQUIRED: 사용자 입력 필요(되묻기 질문) /
                CLARIFICATION_ANSWER: 되묻기 답변 기록 / TASK_FAILED: 실패 / TASK_CANCELLED: 취소·종료.
                사용자 메시지와 이 필드 도입 이전 줄은 null 이며, <b>모르는 값이 오면 무시하고
                평범한 어시스턴트 줄로 그릴 것</b> — 종류는 늘어난다.
                """,
                example = "TASK_PROGRESS", nullable = true)
        ChatMessageKind kind
) {
}
