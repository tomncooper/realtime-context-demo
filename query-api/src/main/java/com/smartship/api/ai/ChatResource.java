package com.smartship.api.ai;

import com.smartship.api.ai.memory.SessionChatMemoryProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * REST resource for the AI chat endpoint.
 *
 * <p>Provides a simple chat interface for interacting with the LogisticsAssistant.
 * Supports session-based chat memory for multi-turn conversations.</p>
 */
@Path("/api/chat")
@Tag(name = "Chat", description = "AI-powered logistics assistant chat endpoints")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChatResource {

    private static final Logger LOG = Logger.getLogger(ChatResource.class);

    @Inject
    LogisticsAssistant assistant;

    @Inject
    SessionChatMemoryProvider memoryProvider;

    /**
     * Send a message to the logistics assistant and receive a response.
     *
     * <p>If no sessionId is provided, a new session will be created.
     * Use the returned sessionId in subsequent requests to maintain conversation context.</p>
     */
    @POST
    @Operation(
        summary = "Chat with the logistics assistant",
        description = "Send a message to the AI-powered logistics assistant. " +
                     "The assistant can answer questions about shipments, customers, and operations " +
                     "using real-time data from Kafka Streams and reference data from PostgreSQL."
    )
    @APIResponse(
        responseCode = "200",
        description = "Successful response from the assistant",
        content = @Content(
            mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = ChatResponse.class)
        )
    )
    @APIResponse(
        responseCode = "400",
        description = "Invalid request (empty message)"
    )
    @APIResponse(
        responseCode = "500",
        description = "Internal server error or LLM unavailable"
    )
    public Response chat(
        @RequestBody(
            description = "Chat request with message and optional session ID",
            required = true,
            content = @Content(schema = @Schema(implementation = ChatRequest.class))
        )
        ChatRequest request
    ) {
        // Validate request
        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(ChatResponse.of("Please provide a message.", null)
                    .withSource("validation"))
                .build();
        }

        // Generate or use existing session ID
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
            LOG.infof("Created new chat session: %s", sessionId);
        }

        LOG.infof("Chat request - Session: %s, Message: %s", sessionId, truncate(request.getMessage(), 100));

        try {
            // Call the AI assistant
            String response = assistant.chat(sessionId, request.getMessage());

            LOG.debugf("Chat response - Session: %s, Response length: %d", sessionId, response.length());

            return Response.ok(
                ChatResponse.of(response, sessionId)
                    .withSources(List.of("kafka-streams", "postgresql"))
            ).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error processing chat request for session: %s", sessionId);

            String errorMessage = "I'm sorry, I encountered an error processing your request. ";
            if (e.getMessage() != null && e.getMessage().contains("connection")) {
                errorMessage += "The LLM service may be temporarily unavailable. Please try again in a moment.";
            } else {
                errorMessage += "Please try again or rephrase your question.";
            }

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(ChatResponse.of(errorMessage, sessionId)
                    .withSource("error"))
                .build();
        }
    }

    /**
     * Clear chat history for a specific session.
     */
    @DELETE
    @Path("/{sessionId}")
    @Operation(
        summary = "Clear chat session",
        description = "Clear the chat history for a specific session ID"
    )
    @APIResponse(responseCode = "204", description = "Session cleared successfully")
    public Response clearSession(@PathParam("sessionId") String sessionId) {
        LOG.infof("Clearing chat session: %s", sessionId);
        memoryProvider.clearSession(sessionId);
        return Response.noContent().build();
    }

    /**
     * Get information about active chat sessions.
     */
    @GET
    @Path("/sessions/count")
    @Operation(
        summary = "Get active session count",
        description = "Returns the number of active chat sessions"
    )
    public Response getActiveSessionCount() {
        int count = memoryProvider.getActiveSessionCount();
        return Response.ok(java.util.Map.of("active_sessions", count)).build();
    }

    /**
     * Health check endpoint for the chat service.
     */
    @GET
    @Path("/health")
    @Operation(
        summary = "Chat service health check",
        description = "Check if the chat service is available"
    )
    public Response healthCheck() {
        return Response.ok(java.util.Map.of(
            "status", "UP",
            "service", "logistics-assistant",
            "active_sessions", memoryProvider.getActiveSessionCount()
        )).build();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
