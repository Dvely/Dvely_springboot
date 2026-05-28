package com.example.dvely.agent.application.port.out;

import java.util.List;

public interface LlmPort {
    String complete(String systemPrompt, List<LlmMessage> messages);
}
