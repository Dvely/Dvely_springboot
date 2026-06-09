package com.example.dvely.agent.application.exception;

public class AgentInputRequiredException extends RuntimeException {

    public AgentInputRequiredException(String question) {
        super(question);
    }
}
