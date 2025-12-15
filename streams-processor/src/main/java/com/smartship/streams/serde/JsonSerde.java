package com.smartship.streams.serde;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * Generic JSON Serde using Jackson for Kafka Streams state stores.
 * Provides type-safe serialization and deserialization for state store values.
 *
 * @param <T> The type to serialize/deserialize
 */
public class JsonSerde<T> implements Serde<T> {

    private static final Logger LOG = LoggerFactory.getLogger(JsonSerde.class);
    private static final ObjectMapper MAPPER = createObjectMapper();

    private final Class<T> type;

    public JsonSerde(Class<T> type) {
        this.type = type;
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @Override
    public Serializer<T> serializer() {
        return new JsonSerializer();
    }

    @Override
    public Deserializer<T> deserializer() {
        return new JsonDeserializer();
    }

    private class JsonSerializer implements Serializer<T> {
        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
            // No additional configuration needed
        }

        @Override
        public byte[] serialize(String topic, T data) {
            if (data == null) {
                return null;
            }
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (JsonProcessingException e) {
                LOG.error("Failed to serialize {} to JSON", type.getSimpleName(), e);
                throw new RuntimeException("JSON serialization failed", e);
            }
        }

        @Override
        public void close() {
            // Nothing to close
        }
    }

    private class JsonDeserializer implements Deserializer<T> {
        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
            // No additional configuration needed
        }

        @Override
        public T deserialize(String topic, byte[] data) {
            if (data == null || data.length == 0) {
                return null;
            }
            try {
                return MAPPER.readValue(data, type);
            } catch (IOException e) {
                LOG.error("Failed to deserialize {} from JSON", type.getSimpleName(), e);
                throw new RuntimeException("JSON deserialization failed", e);
            }
        }

        @Override
        public void close() {
            // Nothing to close
        }
    }

    /**
     * Get the shared ObjectMapper instance for external use.
     * Useful for serializing state store values to JSON responses.
     */
    public static ObjectMapper getObjectMapper() {
        return MAPPER;
    }
}
