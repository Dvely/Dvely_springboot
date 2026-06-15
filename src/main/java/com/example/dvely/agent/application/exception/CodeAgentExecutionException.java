package com.example.dvely.agent.application.exception;

public class CodeAgentExecutionException extends RuntimeException {

    private final String userMessage;
    private final String logExcerpt;
    private final String suggestedFix;

    public CodeAgentExecutionException(String userMessage,
                                       String logExcerpt,
                                       String suggestedFix,
                                       Throwable cause) {
        super(userMessage, cause);
        this.userMessage = userMessage;
        this.logExcerpt = logExcerpt;
        this.suggestedFix = suggestedFix;
    }

    public String userMessage() {
        return userMessage;
    }

    public String logExcerpt() {
        return logExcerpt;
    }

    public String suggestedFix() {
        return suggestedFix;
    }
}
