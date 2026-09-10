package com.example.dvely.chat.application.result;

import com.example.dvely.chat.domain.value.ChatMessageKind;
import java.time.LocalDateTime;

public record MessageResult(
        Long messageId,
        Long conversationId,
        String role,
        String content,
        long tokenCount,
        LocalDateTime createdAt,
        // Present only right after ChatCommandService.sendMessage() successfully submits an
        // Agent plan (see AgentOrchestrator.submit()); null for historical reads (ChatQueryService)
        // and for sendMessage calls where the Decision Agent failed before a task was created.
        String taskId,

        /** 어시스턴트 줄의 종류. 사용자 메시지와 종류가 없는 줄은 null. */
        ChatMessageKind kind
) {
}
