package com.smartship.api.ai;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Request DTO for the chat endpoint.
 */
@RegisterForReflection
public class ChatRequest {

    private String message;
    private String sessionId;

    public ChatRequest() {
    }

    public ChatRequest(String message, String sessionId) {
        this.message = message;
        this.sessionId = sessionId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
