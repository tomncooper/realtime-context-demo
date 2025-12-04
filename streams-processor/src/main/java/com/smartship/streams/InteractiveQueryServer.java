package com.smartship.streams;

import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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
