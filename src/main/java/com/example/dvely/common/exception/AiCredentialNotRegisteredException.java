package com.example.dvely.common.exception;

/**
 * The user asked for a coding agent but has not registered their own API key for the vendor it
 * runs on.
 *
 * <p>Its own type (rather than a generic bad request) because the caller's next step is specific
 * and actionable: register a key for that vendor. The message names the vendor so the client can
 * send the user straight to the right field.</p>
 */
public class AiCredentialNotRegisteredException extends RuntimeException {

    public AiCredentialNotRegisteredException(String message) {
        super(message);
    }
}
