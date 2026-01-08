package com.smartship.api.ai.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Provides session-scoped chat memory for the LogisticsAssistant.
 * Uses in-memory storage with a 20-message window per session.
 *
 * <p>This is a simple implementation suitable for demos. For production,
 * consider using Redis or database-backed storage.</p>
 */
@Singleton
public class SessionChatMemoryProvider implements Supplier<ChatMemoryProvider> {

    private static final Logger LOG = Logger.getLogger(SessionChatMemoryProvider.class);
    private static final int MAX_MESSAGES = 20;

    private final Map<Object, ChatMemory> memories = new ConcurrentHashMap<>();

    @Override
    public ChatMemoryProvider get() {
        return memoryId -> {
            LOG.debugf("Getting chat memory for session: %s", memoryId);
            return memories.computeIfAbsent(memoryId, id -> {
                LOG.infof("Creating new chat memory for session: %s", id);
                return MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(MAX_MESSAGES)
                        .build();
            });
        };
    }

    /**
     * Clears the memory for a specific session.
     *
     * @param sessionId the session ID to clear
     */
    public void clearSession(Object sessionId) {
        ChatMemory removed = memories.remove(sessionId);
        if (removed != null) {
            LOG.infof("Cleared chat memory for session: %s", sessionId);
        }
    }

    /**
     * Returns the number of active sessions.
     *
     * @return count of active chat sessions
     */
    public int getActiveSessionCount() {
        return memories.size();
    }
}
