package com.smartship.api.ai;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Response DTO for the chat endpoint.
 */
@RegisterForReflection
public class ChatResponse {

    private String response;
    private String sessionId;
    private List<String> sources;
    private Instant timestamp;

    public ChatResponse() {
        this.sources = new ArrayList<>();
        this.timestamp = Instant.now();
    }

    public ChatResponse(String response, String sessionId) {
        this();
        this.response = response;
        this.sessionId = sessionId;
    }

    public static ChatResponse of(String response, String sessionId) {
        return new ChatResponse(response, sessionId);
    }

    public ChatResponse withSource(String source) {
        this.sources.add(source);
        return this;
    }

    public ChatResponse withSources(List<String> sources) {
        this.sources.addAll(sources);
        return this;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
