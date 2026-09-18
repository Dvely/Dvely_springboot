package com.example.dvely.agent.infrastructure.codingagent;

/**
 * Thrown when a coding-agent run fails <b>before</b> the CLI starts — a missing image, or a
 * container that could not be created or started.
 *
 * <p>It is a distinct type precisely because this is the only class of failure that is safe to
 * retry: nothing has run yet, so nothing in the user's workspace has been touched. Once the CLI is
 * executing, a failure may leave half-applied edits behind and is surfaced rather than replayed.</p>
 */
public class CodingAgentProvisionException extends RuntimeException {

    public CodingAgentProvisionException(String message, Throwable cause) {
        super(message, cause);
    }

    public CodingAgentProvisionException(String message) {
        super(message);
    }
}
