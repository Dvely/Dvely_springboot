package com.example.dvely.chat.presentation;

import com.example.dvely.chat.application.facade.ChatFacade;
import com.example.dvely.chat.infrastructure.mapper.ChatMapper;
import com.example.dvely.chat.presentation.dto.ConversationResponse;
import com.example.dvely.chat.presentation.dto.MessageResponse;
import com.example.dvely.chat.presentation.dto.SendMessageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Chat", description = "프로젝트별 대화 세션과 메시지 관리 API")
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatFacade chatFacade;
    private final ChatMapper chatMapper;

    @Operation(
            summary = "프로젝트 대화 목록 조회",
            description = "프로젝트에 속한 삭제되지 않은 대화 세션 목록을 최신 수정순으로 조회합니다. " +
                          "프로젝트 작업 화면의 대화 목록을 렌더링할 때 사용합니다."
    )
    @GetMapping("/api/v1/projects/{projectId}/conversations")
    public List<ConversationResponse> getConversations(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "대화 목록을 조회할 프로젝트 ID") @PathVariable Long projectId
    ) {
        return chatFacade.getConversations(userId, projectId).stream()
                .map(chatMapper::toConversationResponse)
                .toList();
    }

    @Operation(
            summary = "프로젝트 대화 생성",
            description = "프로젝트에 새 대화 세션을 생성합니다. " +
                          "요청한 유저가 프로젝트 소유자인지 확인한 뒤 빈 대화 세션을 반환합니다."
    )
    @PostMapping("/api/v1/projects/{projectId}/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse createConversation(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "대화를 생성할 프로젝트 ID") @PathVariable Long projectId
    ) {
        return chatMapper.toConversationResponse(chatFacade.createConversation(userId, projectId));
    }

    @Operation(
            summary = "대화 상세 조회",
            description = "삭제되지 않은 대화 세션의 메타데이터를 조회합니다. " +
                          "대화 ID가 현재 유저 소유가 아니거나 휴지통에 있으면 조회되지 않습니다."
    )
    @GetMapping("/api/v1/conversations/{conversationId}")
    public ConversationResponse getConversation(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "조회할 대화 ID") @PathVariable Long conversationId
    ) {
        return chatMapper.toConversationResponse(chatFacade.getConversation(userId, conversationId));
    }

    @Operation(
            summary = "대화 삭제",
            description = "대화 세션을 휴지통으로 이동합니다. 실제 메시지 데이터는 즉시 삭제하지 않고 deleted/deletedAt 상태를 기록합니다."
    )
    @DeleteMapping("/api/v1/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "휴지통으로 이동할 대화 ID") @PathVariable Long conversationId
    ) {
        chatFacade.deleteConversation(userId, conversationId);
    }

    @Operation(
            summary = "휴지통 대화 목록 조회",
            description = "현재 유저의 휴지통 대화 중 삭제 후 7일이 지나지 않은 대화만 조회합니다. " +
                          "원래 프로젝트가 삭제된 경우 동일 저장소를 연결한 활성 프로젝트 ID로 보정될 수 있습니다."
    )
    @GetMapping("/api/v1/trash/conversations")
    public List<ConversationResponse> getTrashConversations(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId
    ) {
        return chatFacade.getTrashConversations(userId).stream()
                .map(chatMapper::toConversationResponse)
                .toList();
    }

    @Operation(
            summary = "휴지통 대화 복구",
            description = "휴지통의 대화를 복구합니다. 삭제 후 7일이 지난 대화는 복구할 수 없습니다. " +
                          "원래 프로젝트가 삭제된 경우 동일 저장소를 연결한 활성 프로젝트로 복구를 시도합니다."
    )
    @PostMapping("/api/v1/trash/conversations/{conversationId}/restore")
    public ConversationResponse restoreConversation(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "복구할 대화 ID") @PathVariable Long conversationId
    ) {
        return chatMapper.toConversationResponse(chatFacade.restoreConversation(userId, conversationId));
    }

    @Operation(
            summary = "휴지통 대화 영구 삭제",
            description = "휴지통의 대화와 메시지를 즉시 영구 삭제합니다. Agent/Approval/Change 이력은 대화 참조만 해제하고 보존합니다."
    )
    @DeleteMapping("/api/v1/trash/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void permanentlyDeleteConversation(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "영구 삭제할 휴지통 대화 ID") @PathVariable Long conversationId
    ) {
        chatFacade.permanentlyDeleteConversation(userId, conversationId);
    }

    @Operation(
            summary = "대화 메시지 목록 조회",
            description = "대화 세션에 저장된 메시지를 생성순으로 조회합니다. " +
                          "삭제되지 않은 현재 유저 소유 대화에 대해서만 메시지를 반환합니다."
    )
    @GetMapping("/api/v1/conversations/{conversationId}/messages")
    public List<MessageResponse> getMessages(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Parameter(description = "메시지 목록을 조회할 대화 ID") @PathVariable Long conversationId
    ) {
        return chatFacade.getMessages(userId, conversationId).stream()
                .map(chatMapper::toMessageResponse)
                .toList();
    }

    @Operation(
            summary = "대화 메시지 생성",
            description = "대화 세션에 사용자 메시지를 저장합니다. 저장 직후 Decision Agent가 해당 메시지를 동기적으로 판단하여 " +
                          "승인 정책에 따라 Agent 작업을 큐잉(제출)까지 수행합니다(외부 AI API 호출 포함, 승인이 필요한 경우 " +
                          "승인 대기 상태로 큐잉됩니다). 응답(MessageResponse)의 taskId에 생성된 Agent 작업의 ID가 담기며 " +
                          "(판단 실패 시 null), GET /api/v1/agent/tasks/{taskId}로 진행 상황을 폴링할 수 있습니다."
    )
    @PostMapping("/api/v1/conversations/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse sendMessage(@Parameter(hidden = true) @AuthenticationPrincipal Long userId,
                                       @Parameter(description = "메시지를 저장할 대화 ID") @PathVariable Long conversationId,
                                       @Valid @RequestBody SendMessageRequest request) {
        return chatMapper.toMessageResponse(chatFacade.sendMessage(userId, conversationId, request.content()));
    }
}
