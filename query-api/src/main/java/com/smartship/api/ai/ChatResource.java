package com.smartship.api.ai;

import com.smartship.api.ai.memory.SessionChatMemoryProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.*;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url",
                    defaultValue = "http://ollama.smartship.svc.cluster.local:11434")
    String ollamaBaseUrl;

    @ConfigProperty(name = "quarkus.langchain4j.chat-model.provider",
                    defaultValue = "ollama")
    String llmProvider;

    private Client httpClient;

    @PostConstruct
    void init() {
        this.httpClient = ClientBuilder.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();
    }

    @PreDestroy
    void cleanup() {
        if (httpClient != null) {
            httpClient.close();
        }
    }

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
     * Actually tests connectivity to the LLM provider.
     */
    @GET
    @Path("/health")
    @Operation(
        summary = "Chat service health check",
        description = "Check if the chat service and LLM provider are available"
    )
    public Response healthCheck() {
        long startTime = System.currentTimeMillis();

        String status = "DOWN";
        String llmStatus = "UNKNOWN";
        String model = null;
        String errorMessage = null;

        try {
            if ("ollama".equalsIgnoreCase(llmProvider)) {
                // Check Ollama's /api/tags endpoint to verify connectivity and get models
                String tagsUrl = UriBuilder.fromUri(ollamaBaseUrl)
                    .path("api/tags")
                    .build()
                    .toString();

                JsonObject response = httpClient.target(tagsUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .get(JsonObject.class);

                // Check if llama3.2 model is available
                JsonArray models = response.getJsonArray("models");
                if (models != null) {
                    for (JsonValue modelValue : models) {
                        JsonObject modelObj = modelValue.asJsonObject();
                        String modelName = modelObj.getString("name", "");
                        if (modelName.startsWith("llama3.2")) {
                            model = modelName;
                            llmStatus = "UP";
                            status = "UP";
                            break;
                        }
                    }
                    if (model == null && !models.isEmpty()) {
                        // Ollama is up but llama3.2 not found
                        llmStatus = "DEGRADED";
                        status = "DEGRADED";
                        errorMessage = "Model llama3.2 not found, available models: " + models.size();
                    }
                }
            } else {
                // For OpenAI/Anthropic, we can't easily health check without making a real call
                // Just report that we're configured for a cloud provider
                llmStatus = "CONFIGURED";
                status = "UP";
                model = llmProvider;
            }
        } catch (Exception e) {
            LOG.warnf("LLM health check failed: %s", e.getMessage());
            llmStatus = "DOWN";
            errorMessage = e.getMessage();
        }

        long responseTime = System.currentTimeMillis() - startTime;

        Map<String, Object> healthResponse = new LinkedHashMap<>();
        healthResponse.put("status", status);
        healthResponse.put("service", "logistics-assistant");
        healthResponse.put("llm_provider", llmProvider);
        healthResponse.put("llm_status", llmStatus);
        if (model != null) {
            healthResponse.put("model", model);
        }
        if (errorMessage != null) {
            healthResponse.put("error", errorMessage);
        }
        healthResponse.put("response_time_ms", responseTime);
        healthResponse.put("active_sessions", memoryProvider.getActiveSessionCount());

        Response.Status httpStatus = "UP".equals(status) ? Response.Status.OK :
                                     "DEGRADED".equals(status) ? Response.Status.OK :
                                     Response.Status.SERVICE_UNAVAILABLE;

        return Response.status(httpStatus).entity(healthResponse).build();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
