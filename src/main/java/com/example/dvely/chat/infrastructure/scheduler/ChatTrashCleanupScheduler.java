package com.example.dvely.chat.infrastructure.scheduler;

import com.example.dvely.chat.application.command.ChatCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTrashCleanupScheduler {

    private final ChatCommandService chatCommandService;

    @Scheduled(fixedDelayString = "${qeploy.chat.trash-cleanup-interval-ms:3600000}")
    public void purgeExpiredConversations() {
        int purgedCount = chatCommandService.purgeExpiredConversations();
        if (purgedCount > 0) {
            log.info("만료된 휴지통 대화 영구 삭제: count={}", purgedCount);
        }
    }
}
