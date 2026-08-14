package com.example.dvely.agent.domain.value;

/**
 * The per-request model settings a task runs under, resolved once when the plan is created and then
 * carried by the plan for the whole task.
 *
 * <p>Carried on the plan rather than re-read from configuration at each step because a task's steps
 * execute asynchronously, minutes apart and possibly after a retry: re-resolving would let a
 * configuration change mid-task silently switch models between one step and the next, so a task
 * that started on one model could finish on another.</p>
 *
 * @param model    concrete model id — never null once resolved (an absent request value is filled
 *                 in with the provider's configured default)
 * @param thinking reasoning depth; {@link ThinkingLevel#OFF} unless the caller asked for more
 */
public record AiModelOptions(String model, ThinkingLevel thinking) {

    public AiModelOptions {
        thinking = thinking == null ? ThinkingLevel.OFF : thinking;
    }

    /**
     * Settings that defer entirely to server configuration: whatever model the provider is
     * configured with, no extended thinking. This is also what a plan persisted before these
     * fields existed deserializes into, which is why {@code model} is allowed to be null here
     * while a resolved instance always has one.
     */
    public static AiModelOptions defaults() {
        return new AiModelOptions(null, ThinkingLevel.OFF);
    }

    /** The configured model stands in whenever the caller did not name one. */
    public String modelOr(String configuredModel) {
        return model == null || model.isBlank() ? configuredModel : model;
    }
}
