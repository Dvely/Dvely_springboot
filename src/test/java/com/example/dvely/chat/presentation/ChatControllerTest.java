package com.example.dvely.chat.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.chat.application.facade.ChatFacade;
import com.example.dvely.chat.application.result.ConversationResult;
import com.example.dvely.chat.application.result.MessageResult;
import com.example.dvely.chat.infrastructure.mapper.ChatMapper;
import com.example.dvely.chat.presentation.dto.ConversationResponse;
import com.example.dvely.chat.presentation.dto.MessageResponse;
import com.example.dvely.chat.presentation.dto.SendMessageRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatFacade chatFacade;

    @Mock
    private ChatMapper chatMapper;

    @InjectMocks
    private ChatController chatController;

    @Test
    void getConversations_delegatesUsingAuthenticatedUserIdAndProjectId() {
        ConversationResult result = new ConversationResult(
                10L,
                3L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        ConversationResponse response = new ConversationResponse(
                10L,
                3L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(chatFacade.getConversations(1L, 3L)).thenReturn(List.of(result));
        when(chatMapper.toConversationResponse(result)).thenReturn(response);

        List<ConversationResponse> responses = chatController.getConversations(1L, 3L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).conversationId()).isEqualTo(10L);
        assertThat(responses.get(0).projectId()).isEqualTo(3L);
        verify(chatFacade).getConversations(1L, 3L);
    }

    @Test
    void createConversation_delegatesUsingAuthenticatedUserIdAndProjectId() {
        ConversationResult result = new ConversationResult(
                20L,
                8L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        ConversationResponse response = new ConversationResponse(
                20L,
                8L,
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(chatFacade.createConversation(1L, 8L)).thenReturn(result);
        when(chatMapper.toConversationResponse(result)).thenReturn(response);

        ConversationResponse actual = chatController.createConversation(1L, 8L);

        assertThat(actual.conversationId()).isEqualTo(20L);
        assertThat(actual.projectId()).isEqualTo(8L);
        verify(chatFacade).createConversation(1L, 8L);
    }

        @Test
        void getMessages_delegatesUsingAuthenticatedUserIdAndConversationId() {
                MessageResult result = new MessageResult(
                                100L,
                                20L,
                                "user",
                                "hello",
                                0,
                                LocalDateTime.now()
                );
                MessageResponse response = new MessageResponse(
                                100L,
                                20L,
                                "user",
                                "hello",
                                0,
                                LocalDateTime.now()
                );

                when(chatFacade.getMessages(1L, 20L)).thenReturn(List.of(result));
                when(chatMapper.toMessageResponse(result)).thenReturn(response);

                List<MessageResponse> responses = chatController.getMessages(1L, 20L);

                assertThat(responses).hasSize(1);
                assertThat(responses.get(0).messageId()).isEqualTo(100L);
                verify(chatFacade).getMessages(1L, 20L);
        }

        @Test
        void sendMessage_delegatesUsingAuthenticatedUserIdAndConversationId() {
                SendMessageRequest request = new SendMessageRequest("hi");
                MessageResult result = new MessageResult(
                                101L,
                                30L,
                                "user",
                                "hi",
                                0,
                                LocalDateTime.now()
                );
                MessageResponse response = new MessageResponse(
                                101L,
                                30L,
                                "user",
                                "hi",
                                0,
                                LocalDateTime.now()
                );

                when(chatFacade.sendMessage(1L, 30L, "hi")).thenReturn(result);
                when(chatMapper.toMessageResponse(result)).thenReturn(response);

                MessageResponse actual = chatController.sendMessage(1L, 30L, request);

                assertThat(actual.messageId()).isEqualTo(101L);
                verify(chatFacade).sendMessage(1L, 30L, "hi");
        }

        @Test
        void permanentlyDeleteConversation_delegatesUsingAuthenticatedUserId() {
                chatController.permanentlyDeleteConversation(1L, 20L);

                verify(chatFacade).permanentlyDeleteConversation(1L, 20L);
        }
}
