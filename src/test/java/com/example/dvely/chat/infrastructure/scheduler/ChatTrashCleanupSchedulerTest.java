package com.example.dvely.chat.infrastructure.scheduler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.chat.application.command.ChatCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatTrashCleanupSchedulerTest {

    @Mock
    private ChatCommandService chatCommandService;

    @InjectMocks
    private ChatTrashCleanupScheduler scheduler;

    @Test
    void delegatesExpiredConversationPurge() {
        when(chatCommandService.purgeExpiredConversations()).thenReturn(2);

        scheduler.purgeExpiredConversations();

        verify(chatCommandService).purgeExpiredConversations();
    }
}
