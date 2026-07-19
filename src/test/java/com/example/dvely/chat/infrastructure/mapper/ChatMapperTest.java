package com.example.dvely.chat.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.chat.application.result.ConversationResult;
import com.example.dvely.chat.application.result.MessageResult;
import com.example.dvely.chat.presentation.dto.ConversationResponse;
import com.example.dvely.chat.presentation.dto.MessageResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ChatMapper} (no mocks): review follow-up (u2-review.md, F2) — the
 * existing {@code ChatControllerTest} stubs {@code ChatMapper} entirely, so it never exercises
 * this class's real field mapping. In particular, {@code taskId} exposure (added for #39) was
 * only verified end-to-end through a mocked mapper, not against the actual implementation.
 */
class ChatMapperTest {

    private final ChatMapper chatMapper = new ChatMapper();

    @Test
    void toMessageResponseCarriesTheSubmittedTaskId() {
        LocalDateTime createdAt = LocalDateTime.now();
        MessageResult result = new MessageResult(
                101L, 30L, "user", "hi", 0, createdAt, "task-abc123"
        );

        MessageResponse response = chatMapper.toMessageResponse(result);

        assertThat(response.messageId()).isEqualTo(101L);
        assertThat(response.conversationId()).isEqualTo(30L);
        assertThat(response.role()).isEqualTo("user");
        assertThat(response.content()).isEqualTo("hi");
        assertThat(response.tokenCount()).isEqualTo(0);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.taskId()).isEqualTo("task-abc123");
    }

    @Test
    void toMessageResponseLeavesTaskIdNullWhenAbsent() {
        // Historical reads (ChatQueryService) and failed-decision sends (ChatCommandService)
        // both pass a null taskId through MessageResult — the mapper must not fabricate one.
        MessageResult result = new MessageResult(
                102L, 30L, "assistant", "답변입니다", 0, LocalDateTime.now(), null
        );

        MessageResponse response = chatMapper.toMessageResponse(result);

        assertThat(response.taskId()).isNull();
    }

    @Test
    void toConversationResponseMapsAllFields() {
        LocalDateTime now = LocalDateTime.now();
        ConversationResult result = new ConversationResult(
                10L, 3L, "FAQ 섹션 추가", "qeploy-landing",
                true, now.minusDays(1), now.plusDays(6), 6, now.minusDays(3), now
        );

        ConversationResponse response = chatMapper.toConversationResponse(result);

        assertThat(response.conversationId()).isEqualTo(10L);
        assertThat(response.projectId()).isEqualTo(3L);
        assertThat(response.title()).isEqualTo("FAQ 섹션 추가");
        assertThat(response.projectName()).isEqualTo("qeploy-landing");
        assertThat(response.deleted()).isTrue();
        assertThat(response.remainingRetentionDays()).isEqualTo(6);
    }
}
