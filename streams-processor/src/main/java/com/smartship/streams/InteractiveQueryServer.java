package com.smartship.streams;

import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.StreamsMetadata;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Simple HTTP server for Interactive Queries on Kafka Streams state stores.
 */
public class InteractiveQueryServer {

    private static final Logger LOG = LoggerFactory.getLogger(InteractiveQueryServer.class);
    private static final int PORT = 7070;

    private final KafkaStreams streams;
    private HttpServer server;

    public InteractiveQueryServer(KafkaStreams streams) {
        this.streams = streams;
    }

    /**
     * Start the HTTP server.
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Health check endpoint
        server.createContext("/health", exchange -> {
            String response = "{\"status\":\"UP\",\"streams_state\":\"" + streams.state() + "\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });

        // Metadata endpoint: Get all instances for a store
        server.createContext("/metadata/instances", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String[] parts = path.split("/");

                if (parts.length < 4 || parts[3].isEmpty()) {
                    String error = "{\"error\":\"Store name required. Use /metadata/instances/{storeName}\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, error.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(error.getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }

                String storeName = parts[3];
                Collection<StreamsMetadata> metadata = streams.metadataForAllStreamsClients();

                // Filter for instances that have the requested store
                Collection<StreamsMetadata> storeMetadata = metadata.stream()
                    .filter(m -> m.stateStoreNames().contains(storeName))
                    .collect(Collectors.toList());

                // Convert to JSON array
                StringBuilder json = new StringBuilder("[");
                boolean first = true;
                for (StreamsMetadata meta : storeMetadata) {
                    if (!first) {
                        json.append(",");
                    }
                    json.append(String.format(
                        "{\"host\":\"%s\",\"port\":%d,\"stateStoreNames\":[%s]}",
                        meta.host(),
                        meta.port(),
                        meta.stateStoreNames().stream()
                            .map(s -> "\"" + s + "\"")
                            .collect(Collectors.joining(","))
                    ));
                    first = false;
                }
                json.append("]");

                String response = json.toString();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }

                LOG.debug("Served metadata query for store: {}", storeName);

            } catch (Exception e) {
                LOG.error("Error processing metadata query", e);
                String error = "{\"error\":\"" + e.getMessage() + "\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, error.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(error.getBytes(StandardCharsets.UTF_8));
                }
            }
        });

        // Metadata endpoint: Get instance for a specific key
        server.createContext("/metadata/instance-for-key", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String[] parts = path.split("/");

                if (parts.length < 5 || parts[3].isEmpty() || parts[4].isEmpty()) {
                    String error = "{\"error\":\"Store name and key required. Use /metadata/instance-for-key/{storeName}/{key}\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, error.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(error.getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }

                String storeName = parts[3];
                String key = parts[4];

                KeyQueryMetadata metadata = streams.queryMetadataForKey(
                    storeName,
                    key,
                    Serdes.String().serializer()
                );

                if (metadata == null || metadata.activeHost().host().equals("unavailable")) {
                    String error = "{\"error\":\"No instance found for key: " + key + "\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(404, error.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(error.getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }

                String response = String.format(
                    "{\"host\":\"%s\",\"port\":%d,\"partition\":%d}",
                    metadata.activeHost().host(),
                    metadata.activeHost().port(),
                    metadata.partition()
                );

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }

                LOG.debug("Served metadata query for key: {} in store: {}", key, storeName);

            } catch (Exception e) {
                LOG.error("Error processing instance-for-key query", e);
                String error = "{\"error\":\"" + e.getMessage() + "\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, error.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(error.getBytes(StandardCharsets.UTF_8));
                }
            }
        });

        // Query endpoint for shipments by status
        server.createContext("/state/active-shipments-by-status", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String[] parts = path.split("/");

                // Get the state store
                ReadOnlyKeyValueStore<String, Long> store = streams.store(
                    StoreQueryParameters.fromNameAndType(
                        LogisticsTopology.STATE_STORE_NAME,
                        QueryableStoreTypes.keyValueStore()
                    )
                );

                String response;

                if (parts.length > 3 && !parts[3].isEmpty()) {
                    // Query specific status: /state/active-shipments-by-status/{status}
                    String status = parts[3];
                    Long count = store.get(status);

                    if (count == null) {
                        count = 0L;
                    }

                    response = String.format("{\"status\":\"%s\",\"count\":%d}", status, count);
                } else {
                    // Query all statuses: /state/active-shipments-by-status
                    StringBuilder json = new StringBuilder("{");
                    boolean first = true;

                    for (String status : new String[]{"CREATED", "IN_TRANSIT", "DELIVERED"}) {
                        Long count = store.get(status);
                        if (count == null) {
                            count = 0L;
                        }

                        if (!first) {
                            json.append(",");
                        }
                        json.append(String.format("\"%s\":%d", status, count));
                        first = false;
                    }

                    json.append("}");
                    response = json.toString();
                }

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }

                LOG.debug("Served query: {}", exchange.getRequestURI());

            } catch (Exception e) {
                LOG.error("Error processing query", e);
                String error = "{\"error\":\"" + e.getMessage() + "\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, error.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(error.getBytes(StandardCharsets.UTF_8));
                }
            }
        });

        server.setExecutor(null); // Use default executor
        server.start();

        LOG.info("Interactive Query Server started on port {}", PORT);
        LOG.info("Available endpoints:");
        LOG.info("  GET /health");
        LOG.info("  GET /metadata/instances/{{storeName}}");
        LOG.info("  GET /metadata/instance-for-key/{{storeName}}/{{key}}");
        LOG.info("  GET /state/active-shipments-by-status");
        LOG.info("  GET /state/active-shipments-by-status/{{status}}");
    }

    /**
     * Stop the HTTP server.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            LOG.info("Interactive Query Server stopped");
        }
    }
}
