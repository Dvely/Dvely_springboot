package com.example.dvely.agent.application.port.out;

import com.example.dvely.agent.domain.value.AiModelOptions;
import java.util.List;
import java.util.Map;

/**
 * A provider that can answer with tool calls, which is what the CODE agent's loop runs on.
 *
 * <p>Separate from {@link LlmPort}: that one returns a finished answer, while a tool loop needs the
 * calls the model wants executed and the raw content blocks to append back onto the transcript. The
 * transcript shape is still the provider's own — Anthropic's {@code tool_result} blocks and
 * OpenAI's {@code role: "tool"} messages are not interchangeable — so this interface buys the loop
 * a single type for the clients that share one shape, not a uniform transcript across all of
 * them.</p>
 */
public interface LlmToolPort {

    LlmToolResponse completeWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<ToolDefinition> tools,
            AiModelOptions modelOptions);
}
