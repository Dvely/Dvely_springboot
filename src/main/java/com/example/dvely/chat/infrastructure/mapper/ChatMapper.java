package com.example.dvely.chat.infrastructure.mapper;

import com.example.dvely.chat.application.pin.PreviewPinInput;
import com.example.dvely.chat.application.result.ConversationResult;
import com.example.dvely.chat.application.result.MessageResult;
import com.example.dvely.chat.presentation.dto.ConversationResponse;
import com.example.dvely.chat.presentation.dto.MessageResponse;
import com.example.dvely.chat.presentation.dto.SendMessageRequest;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ChatMapper {

    public ConversationResponse toConversationResponse(ConversationResult result) {
        return new ConversationResponse(
                result.conversationId(),
                result.projectId(),
                result.title(),
                result.projectName(),
                result.deleted(),
                result.deletedAt(),
                result.retentionExpiresAt(),
                result.remainingRetentionDays(),
                result.createdAt(),
                result.updatedAt()
        );
    }

    public MessageResponse toMessageResponse(MessageResult result) {
        return new MessageResponse(
                result.messageId(),
                result.conversationId(),
                result.role(),
                result.content(),
                result.tokenCount(),
                result.createdAt(),
                result.taskId(),
                result.kind(),
                result.pins().stream()
                        .map(pin -> new MessageResponse.PinResponse(pin.position(), pin.label()))
                        .toList()
        );
    }

    public List<PreviewPinInput> toPinInputs(List<SendMessageRequest.PinRequest> pins) {
        if (pins == null) {
            return List.of();
        }
        // 빈 자리(null)도 넘긴다 — 번호(①②)가 밀리지 않게, 서버가 그 자리를 형식 오류로 거절한다
        return pins.stream()
                .map(pin -> pin == null ? new PreviewPinInput(null, null) : new PreviewPinInput(pin.result(), pin.scope()))
                .toList();
    }
}
