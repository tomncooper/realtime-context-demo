package com.smartship.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartship.streams.model.*;
import com.smartship.streams.serde.JsonSerde;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.StreamsMetadata;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HTTP server for Interactive Queries on Kafka Streams state stores.
 * Phase 3: Supports all 6 state stores including windowed stores.
 */
public class InteractiveQueryServer {

    private static final Logger LOG = LoggerFactory.getLogger(InteractiveQueryServer.class);
    private static final int PORT = 7070;
    private static final ObjectMapper MAPPER = JsonSerde.getObjectMapper();

    // All known status values for shipments
    private static final String[] ALL_SHIPMENT_STATUSES = {
        "CREATED", "PICKED", "PACKED", "DISPATCHED", "IN_TRANSIT",
        "OUT_FOR_DELIVERY", "DELIVERED", "EXCEPTION", "CANCELLED"
    };

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
        server.createContext("/health", this::handleHealth);

        // Metadata endpoints
        server.createContext("/metadata/instances", this::handleMetadataInstances);
        server.createContext("/metadata/instance-for-key", this::handleMetadataForKey);

        // State store query endpoints
        server.createContext("/state/active-shipments-by-status", this::handleActiveShipmentsByStatus);
        server.createContext("/state/vehicle-current-state", this::handleVehicleCurrentState);
        server.createContext("/state/shipments-by-customer", this::handleShipmentsByCustomer);
        server.createContext("/state/late-shipments", this::handleLateShipments);
        server.createContext("/state/warehouse-realtime-metrics", this::handleWarehouseMetrics);
        server.createContext("/state/hourly-delivery-performance", this::handleHourlyPerformance);

        server.setExecutor(null); // Use default executor
        server.start();

        LOG.info("Interactive Query Server started on port {}", PORT);
        logAvailableEndpoints();
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

    // ===========================================
    // Health and Metadata Endpoints
    // ===========================================

    private void handleHealth(HttpExchange exchange) throws IOException {
        String response = "{\"status\":\"UP\",\"streams_state\":\"" + streams.state() + "\"}";
        sendResponse(exchange, 200, response);
    }

    private void handleMetadataInstances(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");

            if (parts.length < 4 || parts[3].isEmpty()) {
                sendError(exchange, 400, "Store name required. Use /metadata/instances/{storeName}");
                return;
            }

            String storeName = parts[3];
            Collection<StreamsMetadata> metadata = streams.metadataForAllStreamsClients();

            Collection<StreamsMetadata> storeMetadata = metadata.stream()
                .filter(m -> m.stateStoreNames().contains(storeName))
                .collect(Collectors.toList());

            List<Map<String, Object>> result = new ArrayList<>();
            for (StreamsMetadata meta : storeMetadata) {
                Map<String, Object> instance = new LinkedHashMap<>();
                instance.put("host", meta.host());
                instance.put("port", meta.port());
                instance.put("stateStoreNames", new ArrayList<>(meta.stateStoreNames()));
                result.add(instance);
            }

            sendJsonResponse(exchange, result);
            LOG.debug("Served metadata query for store: {}", storeName);

        } catch (Exception e) {
            LOG.error("Error processing metadata query", e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleMetadataForKey(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");

            if (parts.length < 5 || parts[3].isEmpty() || parts[4].isEmpty()) {
                sendError(exchange, 400, "Store name and key required. Use /metadata/instance-for-key/{storeName}/{key}");
                return;
            }

            String storeName = parts[3];
            String key = parts[4];

            KeyQueryMetadata metadata = streams.queryMetadataForKey(
                storeName, key, Serdes.String().serializer()
            );

            if (metadata == null || metadata.activeHost().host().equals("unavailable")) {
                sendError(exchange, 404, "No instance found for key: " + key);
                return;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("host", metadata.activeHost().host());
            result.put("port", metadata.activeHost().port());
            result.put("partition", metadata.partition());

            sendJsonResponse(exchange, result);
            LOG.debug("Served metadata query for key: {} in store: {}", key, storeName);

        } catch (Exception e) {
            LOG.error("Error processing instance-for-key query", e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ===========================================
    // State Store 1: active-shipments-by-status
    // ===========================================

    private void handleActiveShipmentsByStatus(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");

            ReadOnlyKeyValueStore<String, Long> store = streams.store(
                StoreQueryParameters.fromNameAndType(
                    LogisticsTopology.ACTIVE_SHIPMENTS_BY_STATUS_STORE,
                    QueryableStoreTypes.keyValueStore()
                )
            );

            if (parts.length > 3 && !parts[3].isEmpty()) {
                // Query specific status
                String status = parts[3];
                Long count = store.get(status);
                if (count == null) count = 0L;

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", status);
                result.put("count", count);
                sendJsonResponse(exchange, result);
            } else {
                // Query all statuses
                Map<String, Long> counts = new LinkedHashMap<>();
                for (String status : ALL_SHIPMENT_STATUSES) {
                    Long count = store.get(status);
                    counts.put(status, count != null ? count : 0L);
                }
                sendJsonResponse(exchange, counts);
            }

            LOG.debug("Served query: {}", exchange.getRequestURI());

        } catch (Exception e) {
            LOG.error("Error processing active-shipments-by-status query", e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ===========================================
    // State Store 2: vehicle-current-state
    // ===========================================

    private void handleVehicleCurrentState(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");

            ReadOnlyKeyValueStore<String, VehicleState> store = streams.store(
                StoreQueryParameters.fromNameAndType(
                    LogisticsTopology.VEHICLE_STATE_STORE,
                    QueryableStoreTypes.keyValueStore()
                )
            );

            if (parts.length > 3 && !parts[3].isEmpty()) {
                // Query specific vehicle
                String vehicleId = parts[3];
                VehicleState state = store.get(vehicleId);

                if (state == null) {
                    sendError(exchange, 404, "Vehicle not found: " + vehicleId);
                    return;
                }

                sendJsonResponse(exchange, state);
            } else {
                // Query all vehicles
                List<VehicleState> vehicles = new ArrayList<>();
                try (KeyValueIterator<String, VehicleState> iter = store.all()) {
                    while (iter.hasNext()) {
                        vehicles.add(iter.next().value);
                    }
                }
                sendJsonResponse(exchange, vehicles);
            }

            LOG.debug("Served query: {}", exchange.getRequestURI());

        } catch (Exception e) {
            LOG.error("Error processing vehicle-current-state query", e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ===========================================
    // State Store 3: shipments-by-customer
    // ===========================================

    private void handleShipmentsByCustomer(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");

            ReadOnlyKeyValueStore<String, CustomerShipmentStats> store = streams.store(
                StoreQueryParameters.fromNameAndType(
                    LogisticsTopology.SHIPMENTS_BY_CUSTOMER_STORE,
                    QueryableStoreTypes.keyValueStore()
                )
            );

            if (parts.length > 3 && !parts[3].isEmpty()) {
                // Query specific customer
                String customerId = parts[3];
                CustomerShipmentStats stats = store.get(customerId);

                if (stats == null) {
                    sendError(exchange, 404, "Customer not found: " + customerId);
                    return;
                }

                sendJsonResponse(exchange, stats);
            } else {
                // Query all customers
                List<CustomerShipmentStats> customers = new ArrayList<>();
                try (KeyValueIterator<String, CustomerShipmentStats> iter = store.all()) {
                    while (iter.hasNext()) {
                        customers.add(iter.next().value);
                    }
                }
                sendJsonResponse(exchange, customers);
            }

            LOG.debug("Served query: {}", exchange.getRequestURI());

        } catch (Exception e) {
            LOG.error("Error processing shipments-by-customer query", e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ===========================================
    // State Store 4: late-shipments
    // ===========================================

    private void handleLateShipments(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");

            ReadOnlyKeyValueStore<String, LateShipmentDetails> store = streams.store(
                StoreQueryParameters.fromNameAndType(
                    LogisticsTopology.LATE_SHIPMENTS_STORE,
                    QueryableStoreTypes.keyValueStore()
                )
            );

            if (parts.length > 3 && !parts[3].isEmpty()) {
                // Query specific late shipment
                String shipmentId = parts[3];
                LateShipmentDetails details = store.get(shipmentId);

                if (details == null) {
                    sendError(exchange, 404, "Late shipment not found: " + shipmentId);
                    return;
                }

                sendJsonResponse(exchange, details);
            } else {
                // Query all late shipments
                List<LateShipmentDetails> lateShipments = new ArrayList<>();
                try (KeyValueIterator<String, LateShipmentDetails> iter = store.all()) {
                    while (iter.hasNext()) {
                        LateShipmentDetails details = iter.next().value;
                        if (details != null) {
                            lateShipments.add(details);
                        }
                    }
                }
                sendJsonResponse(exchange, lateShipments);
            }

            LOG.debug("Served query: {}", exchange.getRequestURI());

        } catch (Exception e) {
            LOG.error("Error processing late-shipments query", e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ===========================================
    // State Store 5: warehouse-realtime-metrics (Windowed)
    // ===========================================

    private void handleWarehouseMetrics(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");

            ReadOnlyWindowStore<String, WarehouseMetrics> store = streams.store(
                StoreQueryParameters.fromNameAndType(
                    LogisticsTopology.WAREHOUSE_METRICS_STORE,
                    QueryableStoreTypes.windowStore()
                )
            );

            // Get current time window
            Instant now = Instant.now();
            Instant windowStart = now.minusSeconds(900); // 15 minutes ago

            if (parts.length > 3 && !parts[3].isEmpty()) {
                // Query specific warehouse
                String warehouseId = parts[3];

                List<Map<String, Object>> windowedResults = new ArrayList<>();
                try (WindowStoreIterator<WarehouseMetrics> iter = store.fetch(warehouseId, windowStart, now)) {
                    while (iter.hasNext()) {
                        KeyValue<Long, WarehouseMetrics> kv = iter.next();
                        if (kv.value != null) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("window_start", kv.key);
                            result.put("window_start_iso", Instant.ofEpochMilli(kv.key).toString());
                            result.put("metrics", kv.value);
                            windowedResults.add(result);
                        }
                    }
                }

                if (windowedResults.isEmpty()) {
                    sendError(exchange, 404, "No metrics found for warehouse: " + warehouseId);
                    return;
                }

                sendJsonResponse(exchange, windowedResults);
            } else {
                // Query all warehouses (current window)
                Map<String, List<Map<String, Object>>> allMetrics = new LinkedHashMap<>();

                try (KeyValueIterator<Windowed<String>, WarehouseMetrics> iter = store.fetchAll(windowStart, now)) {
                    while (iter.hasNext()) {
                        KeyValue<Windowed<String>, WarehouseMetrics> kv = iter.next();
                        if (kv.value != null) {
                            String warehouseId = kv.key.key();
                            allMetrics.computeIfAbsent(warehouseId, k -> new ArrayList<>());

                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("window_start", kv.key.window().start());
                            result.put("window_end", kv.key.window().end());
                            result.put("metrics", kv.value);
                            allMetrics.get(warehouseId).add(result);
                        }
                    }
                }

                sendJsonResponse(exchange, allMetrics);
            }

            LOG.debug("Served query: {}", exchange.getRequestURI());

        } catch (Exception e) {
            LOG.error("Error processing warehouse-realtime-metrics query", e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ===========================================
    // State Store 6: hourly-delivery-performance (Windowed)
    // ===========================================

    private void handleHourlyPerformance(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");

            ReadOnlyWindowStore<String, DeliveryStats> store = streams.store(
                StoreQueryParameters.fromNameAndType(
                    LogisticsTopology.HOURLY_PERFORMANCE_STORE,
                    QueryableStoreTypes.windowStore()
                )
            );

            // Get current time window
            Instant now = Instant.now();
            Instant windowStart = now.minusSeconds(3600); // 1 hour ago

            if (parts.length > 3 && !parts[3].isEmpty()) {
                // Query specific warehouse
                String warehouseId = parts[3];

                List<Map<String, Object>> windowedResults = new ArrayList<>();
                try (WindowStoreIterator<DeliveryStats> iter = store.fetch(warehouseId, windowStart, now)) {
                    while (iter.hasNext()) {
                        KeyValue<Long, DeliveryStats> kv = iter.next();
                        if (kv.value != null) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("window_start", kv.key);
                            result.put("window_start_iso", Instant.ofEpochMilli(kv.key).toString());
                            result.put("stats", kv.value);
                            windowedResults.add(result);
                        }
                    }
                }

                if (windowedResults.isEmpty()) {
                    sendError(exchange, 404, "No performance data found for warehouse: " + warehouseId);
                    return;
                }

                sendJsonResponse(exchange, windowedResults);
            } else {
                // Query all warehouses (current window)
                Map<String, List<Map<String, Object>>> allStats = new LinkedHashMap<>();

                try (KeyValueIterator<Windowed<String>, DeliveryStats> iter = store.fetchAll(windowStart, now)) {
                    while (iter.hasNext()) {
                        KeyValue<Windowed<String>, DeliveryStats> kv = iter.next();
                        if (kv.value != null) {
                            String warehouseId = kv.key.key();
                            allStats.computeIfAbsent(warehouseId, k -> new ArrayList<>());

                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("window_start", kv.key.window().start());
                            result.put("window_end", kv.key.window().end());
                            result.put("stats", kv.value);
                            allStats.get(warehouseId).add(result);
                        }
                    }
                }

                sendJsonResponse(exchange, allStats);
            }

            LOG.debug("Served query: {}", exchange.getRequestURI());

        } catch (Exception e) {
            LOG.error("Error processing hourly-delivery-performance query", e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    // ===========================================
    // Helper Methods
    // ===========================================

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJsonResponse(HttpExchange exchange, Object data) throws IOException {
        String json = MAPPER.writeValueAsString(data);
        sendResponse(exchange, 200, json);
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String response = "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        sendResponse(exchange, statusCode, response);
    }

    private void logAvailableEndpoints() {
        LOG.info("Available endpoints:");
        LOG.info("  GET /health");
        LOG.info("  GET /metadata/instances/{{storeName}}");
        LOG.info("  GET /metadata/instance-for-key/{{storeName}}/{{key}}");
        LOG.info("  GET /state/active-shipments-by-status");
        LOG.info("  GET /state/active-shipments-by-status/{{status}}");
        LOG.info("  GET /state/vehicle-current-state");
        LOG.info("  GET /state/vehicle-current-state/{{vehicleId}}");
        LOG.info("  GET /state/shipments-by-customer");
        LOG.info("  GET /state/shipments-by-customer/{{customerId}}");
        LOG.info("  GET /state/late-shipments");
        LOG.info("  GET /state/late-shipments/{{shipmentId}}");
        LOG.info("  GET /state/warehouse-realtime-metrics");
        LOG.info("  GET /state/warehouse-realtime-metrics/{{warehouseId}}");
        LOG.info("  GET /state/hourly-delivery-performance");
        LOG.info("  GET /state/hourly-delivery-performance/{{warehouseId}}");
    }
}
