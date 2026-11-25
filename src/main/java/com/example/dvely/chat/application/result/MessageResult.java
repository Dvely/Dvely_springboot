package com.example.dvely.chat.application.result;

import com.example.dvely.chat.domain.model.ChatMessagePinLabel;
import com.example.dvely.chat.domain.value.ChatMessageKind;
import java.time.LocalDateTime;
import java.util.List;

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
        ChatMessageKind kind,

        /** 서버 프리뷰에서 짚어 붙인 곳(§16.6). 번호 순서, 없으면 빈 목록. */
        List<ChatMessagePinLabel> pins
) {

    public MessageResult {
        pins = pins == null ? List.of() : List.copyOf(pins);
    }

    public MessageResult(Long messageId, Long conversationId, String role, String content, long tokenCount,
                         LocalDateTime createdAt, String taskId, ChatMessageKind kind) {
        this(messageId, conversationId, role, content, tokenCount, createdAt, taskId, kind, List.of());
    }
}
