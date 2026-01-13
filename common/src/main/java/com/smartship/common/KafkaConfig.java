package com.smartship.common;

import io.apicurio.registry.serde.config.SerdeConfig;
import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.apicurio.registry.serde.avro.AvroKafkaSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.errors.LogAndContinueProcessingExceptionHandler;

import java.util.Properties;

/**
 * Centralized Kafka configuration for producers, consumers, and streams.
 */
public class KafkaConfig {

    private static final String BOOTSTRAP_SERVERS =
        System.getenv().getOrDefault(
            "KAFKA_BOOTSTRAP_SERVERS",
            "events-cluster-kafka-bootstrap.smartship.svc.cluster.local:9092"
        );

    private static final String APICURIO_REGISTRY_URL =
        System.getenv().getOrDefault(
            "APICURIO_REGISTRY_URL",
            "http://apicurio-registry.smartship.svc.cluster.local:8080/apis/registry/v2"
        );

    /**
     * Create Kafka producer configuration with Avro serialization.
     *
     * @param clientId Unique client identifier
     * @return Producer properties
     */
    public static Properties createProducerConfig(String clientId) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, AvroKafkaSerializer.class.getName());

        // Apicurio Registry configuration
        props.put(SerdeConfig.REGISTRY_URL, APICURIO_REGISTRY_URL);
        props.put(SerdeConfig.AUTO_REGISTER_ARTIFACT, "true");

        // Producer reliability settings
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, "3");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        return props;
    }

    /**
     * Create Kafka consumer configuration with Avro deserialization.
     *
     * @param groupId Consumer group identifier
     * @param clientId Unique client identifier
     * @return Consumer properties
     */
    public static Properties createConsumerConfig(String groupId, String clientId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, AvroKafkaDeserializer.class.getName());

        // Apicurio Registry configuration
        props.put(SerdeConfig.REGISTRY_URL, APICURIO_REGISTRY_URL);

        // Consumer settings
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        return props;
    }

    /**
     * Create Kafka Streams configuration.
     *
     * @param applicationId Streams application identifier
     * @return Streams properties
     */
    public static Properties createStreamsConfig(String applicationId) {
        return createStreamsConfig(applicationId, null);
    }

    /**
     * Create Kafka Streams configuration with optional application server.
     *
     * @param applicationId Streams application identifier
     * @param applicationServer Optional application server (host:port) for Interactive Queries
     * @return Streams properties
     */
    public static Properties createStreamsConfig(String applicationId, String applicationServer) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, org.apache.kafka.common.serialization.Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, org.apache.kafka.common.serialization.Serdes.String().getClass());

        // Apicurio Registry configuration
        props.put(SerdeConfig.REGISTRY_URL, APICURIO_REGISTRY_URL);

        // Streams settings
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        props.put(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams/" + applicationId);
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 10 * 1024 * 1024L); // 10MB cache

        // Exception handlers - continue on error instead of crashing the application
        // Use built-in handlers for deserialization and processing exceptions
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
            LogAndContinueExceptionHandler.class);
        props.put(StreamsConfig.PROCESSING_EXCEPTION_HANDLER_CLASS_CONFIG,
            LogAndContinueProcessingExceptionHandler.class);
        // Custom handler for production exceptions (no built-in "continue" handler exists)
        props.put(StreamsConfig.PRODUCTION_EXCEPTION_HANDLER_CLASS_CONFIG,
            "com.smartship.streams.handlers.LogAndContinueProductionHandler");

        // Set application.server for Interactive Queries if provided
        if (applicationServer != null && !applicationServer.isEmpty()) {
            props.put(StreamsConfig.APPLICATION_SERVER_CONFIG, applicationServer);
        }

        return props;
    }

    /**
     * Get the configured Kafka bootstrap servers.
     *
     * @return Bootstrap servers string
     */
    public static String getBootstrapServers() {
        return BOOTSTRAP_SERVERS;
    }

    /**
     * Get the configured Apicurio Registry URL.
     *
     * @return Registry URL string
     */
    public static String getApicurioRegistryUrl() {
        return APICURIO_REGISTRY_URL;
    }
}
