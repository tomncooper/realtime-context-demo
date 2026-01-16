package com.smartship.api;

import com.smartship.api.model.StreamsInstanceMetadata;
import com.smartship.api.services.StreamsInstanceDiscoveryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Service for querying Kafka Streams state stores via Interactive Queries.
 * Supports all 9 state stores with distributed queries.
 */
@ApplicationScoped
public class KafkaStreamsQueryService {

    private static final Logger LOG = Logger.getLogger(KafkaStreamsQueryService.class);

    // State store names (must match LogisticsTopology constants)
    private static final String ACTIVE_SHIPMENTS_BY_STATUS_STORE = "active-shipments-by-status";
    private static final String VEHICLE_STATE_STORE = "vehicle-current-state";
    private static final String SHIPMENTS_BY_CUSTOMER_STORE = "shipments-by-customer";
    private static final String LATE_SHIPMENTS_STORE = "late-shipments";
    private static final String WAREHOUSE_METRICS_STORE = "warehouse-realtime-metrics";
    private static final String HOURLY_PERFORMANCE_STORE = "hourly-delivery-performance";

    // Order state stores
    private static final String ORDER_STATE_STORE = "order-current-state";
    private static final String ORDERS_BY_CUSTOMER_STORE = "orders-by-customer";
    private static final String ORDER_SLA_TRACKING_STORE = "order-sla-tracking";

    @Inject
    StreamsInstanceDiscoveryService discoveryService;

    private final Client client;
    private final ExecutorService executorService;

    public KafkaStreamsQueryService() {
        this.client = ClientBuilder.newClient();
        this.executorService = Executors.newFixedThreadPool(10);
    }

    // ===========================================
    // State Store 1: active-shipments-by-status
    // ===========================================

    /**
     * Get shipment count for a specific status.
     */
    public Long getShipmentCountByStatus(String status) {
        LOG.debugf("Querying shipment count for status: %s", status);

        try {
            StreamsInstanceMetadata instance = discoveryService.findInstanceForKey(ACTIVE_SHIPMENTS_BY_STATUS_STORE, status);

            if (instance == null) {
                LOG.warnf("No instance found for status: %s", status);
                return 0L;
            }

            String url = instance.getUrl() + "/state/active-shipments-by-status/" + status;
            LOG.debugf("Querying instance: %s", url);

            JsonObject json = client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .get(JsonObject.class);

            return json.getJsonNumber("count").longValue();

        } catch (Exception e) {
            LOG.errorf(e, "Error querying shipment count for status: %s", status);
            throw new RuntimeException("Failed to query Kafka Streams state store", e);
        }
    }

    /**
     * Get counts for all shipment statuses.
     */
    public Map<String, Object> getAllStatusCounts() {
        LOG.debug("Querying all status counts from all instances");

        try {
            List<StreamsInstanceMetadata> instances = discoveryService.discoverInstances(ACTIVE_SHIPMENTS_BY_STATUS_STORE);

            if (instances.isEmpty()) {
                LOG.warn("No instances found for state store");
                return new HashMap<>();
            }

            LOG.infof("Querying %d instances in parallel", instances.size());

            List<CompletableFuture<Map<String, Long>>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                    () -> queryJsonNumberMap(instance, "/state/active-shipments-by-status"),
                    executorService
                ))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            Map<String, Long> mergedCounts = new HashMap<>();
            for (CompletableFuture<Map<String, Long>> future : futures) {
                Map<String, Long> instanceCounts = future.get();
                instanceCounts.forEach((status, count) ->
                    mergedCounts.merge(status, count, Long::sum)
                );
            }

            LOG.infof("Aggregated counts from %d instances: %s", instances.size(), mergedCounts);
            return new HashMap<>(mergedCounts);

        } catch (Exception e) {
            LOG.error("Error querying all status counts", e);
            throw new RuntimeException("Failed to query Kafka Streams state store", e);
        }
    }

    // ===========================================
    // State Store 2: vehicle-current-state
    // ===========================================

    /**
     * Get current state for a specific vehicle.
     */
    public Map<String, Object> getVehicleState(String vehicleId) {
        LOG.debugf("Querying vehicle state for: %s", vehicleId);

        try {
            StreamsInstanceMetadata instance = discoveryService.findInstanceForKey(VEHICLE_STATE_STORE, vehicleId);

            if (instance == null) {
                LOG.warnf("No instance found for vehicle: %s", vehicleId);
                return null;
            }

            String url = instance.getUrl() + "/state/vehicle-current-state/" + vehicleId;
            return queryJsonObject(instance, "/state/vehicle-current-state/" + vehicleId);

        } catch (Exception e) {
            LOG.errorf(e, "Error querying vehicle state for: %s", vehicleId);
            throw new RuntimeException("Failed to query vehicle state", e);
        }
    }

    /**
     * Get current state for all vehicles.
     */
    public List<Map<String, Object>> getAllVehicleStates() {
        LOG.debug("Querying all vehicle states");

        try {
            List<StreamsInstanceMetadata> instances = discoveryService.discoverInstances(VEHICLE_STATE_STORE);

            if (instances.isEmpty()) {
                return new ArrayList<>();
            }

            List<CompletableFuture<List<Map<String, Object>>>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                    () -> queryJsonArray(instance, "/state/vehicle-current-state"),
                    executorService
                ))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Map<String, Object>> allVehicles = new ArrayList<>();
            for (CompletableFuture<List<Map<String, Object>>> future : futures) {
                allVehicles.addAll(future.get());
            }

            LOG.infof("Retrieved %d vehicle states", allVehicles.size());
            return allVehicles;

        } catch (Exception e) {
            LOG.error("Error querying all vehicle states", e);
            throw new RuntimeException("Failed to query vehicle states", e);
        }
    }

    // ===========================================
    // State Store 3: shipments-by-customer
    // ===========================================

    /**
     * Get shipment stats for a specific customer.
     */
    public Map<String, Object> getCustomerShipmentStats(String customerId) {
        LOG.debugf("Querying shipment stats for customer: %s", customerId);

        try {
            StreamsInstanceMetadata instance = discoveryService.findInstanceForKey(SHIPMENTS_BY_CUSTOMER_STORE, customerId);

            if (instance == null) {
                LOG.warnf("No instance found for customer: %s", customerId);
                return null;
            }

            return queryJsonObject(instance, "/state/shipments-by-customer/" + customerId);

        } catch (Exception e) {
            LOG.errorf(e, "Error querying customer stats for: %s", customerId);
            throw new RuntimeException("Failed to query customer stats", e);
        }
    }

    /**
     * Get shipment stats for all customers.
     */
    public List<Map<String, Object>> getAllCustomerStats() {
        LOG.debug("Querying all customer shipment stats");

        try {
            List<StreamsInstanceMetadata> instances = discoveryService.discoverInstances(SHIPMENTS_BY_CUSTOMER_STORE);

            if (instances.isEmpty()) {
                return new ArrayList<>();
            }

            List<CompletableFuture<List<Map<String, Object>>>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                    () -> queryJsonArray(instance, "/state/shipments-by-customer"),
                    executorService
                ))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Map<String, Object>> allStats = new ArrayList<>();
            for (CompletableFuture<List<Map<String, Object>>> future : futures) {
                allStats.addAll(future.get());
            }

            LOG.infof("Retrieved stats for %d customers", allStats.size());
            return allStats;

        } catch (Exception e) {
            LOG.error("Error querying all customer stats", e);
            throw new RuntimeException("Failed to query customer stats", e);
        }
    }

    // ===========================================
    // State Store 4: late-shipments
    // ===========================================

    /**
     * Get details for a specific late shipment.
     */
    public Map<String, Object> getLateShipment(String shipmentId) {
        LOG.debugf("Querying late shipment: %s", shipmentId);

        try {
            StreamsInstanceMetadata instance = discoveryService.findInstanceForKey(LATE_SHIPMENTS_STORE, shipmentId);

            if (instance == null) {
                LOG.warnf("No instance found for late shipment: %s", shipmentId);
                return null;
            }

            return queryJsonObject(instance, "/state/late-shipments/" + shipmentId);

        } catch (Exception e) {
            LOG.errorf(e, "Error querying late shipment: %s", shipmentId);
            throw new RuntimeException("Failed to query late shipment", e);
        }
    }

    /**
     * Get all late shipments.
     */
    public List<Map<String, Object>> getAllLateShipments() {
        LOG.debug("Querying all late shipments");

        try {
            List<StreamsInstanceMetadata> instances = discoveryService.discoverInstances(LATE_SHIPMENTS_STORE);

            if (instances.isEmpty()) {
                return new ArrayList<>();
            }

            List<CompletableFuture<List<Map<String, Object>>>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                    () -> queryJsonArray(instance, "/state/late-shipments"),
                    executorService
                ))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Map<String, Object>> allLate = new ArrayList<>();
            for (CompletableFuture<List<Map<String, Object>>> future : futures) {
                allLate.addAll(future.get());
            }

            LOG.infof("Retrieved %d late shipments", allLate.size());
            return allLate;

        } catch (Exception e) {
            LOG.error("Error querying all late shipments", e);
            throw new RuntimeException("Failed to query late shipments", e);
        }
    }

    // ===========================================
    // State Store 5: warehouse-realtime-metrics
    // ===========================================

    /**
     * Get metrics for a specific warehouse (current window).
     */
    public List<Map<String, Object>> getWarehouseMetrics(String warehouseId) {
        LOG.debugf("Querying warehouse metrics for: %s", warehouseId);

        try {
            // For windowed stores, we query all instances since data is partitioned by time windows
            List<StreamsInstanceMetadata> instances = discoveryService.discoverInstances(WAREHOUSE_METRICS_STORE);

            if (instances.isEmpty()) {
                return new ArrayList<>();
            }

            List<CompletableFuture<List<Map<String, Object>>>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                    () -> queryJsonArray(instance, "/state/warehouse-realtime-metrics/" + warehouseId),
                    executorService
                ))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Map<String, Object>> allMetrics = new ArrayList<>();
            for (CompletableFuture<List<Map<String, Object>>> future : futures) {
                allMetrics.addAll(future.get());
            }

            return allMetrics;

        } catch (Exception e) {
            LOG.errorf(e, "Error querying warehouse metrics for: %s", warehouseId);
            throw new RuntimeException("Failed to query warehouse metrics", e);
        }
    }

    /**
     * Get metrics for all warehouses (current window).
     */
    public Map<String, Object> getAllWarehouseMetrics() {
        LOG.debug("Querying all warehouse metrics");

        try {
            List<StreamsInstanceMetadata> instances = discoveryService.discoverInstances(WAREHOUSE_METRICS_STORE);

            if (instances.isEmpty()) {
                return new HashMap<>();
            }

            List<CompletableFuture<Map<String, Object>>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                    () -> queryJsonObject(instance, "/state/warehouse-realtime-metrics"),
                    executorService
                ))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Merge results by warehouse ID
            Map<String, Object> merged = new HashMap<>();
            for (CompletableFuture<Map<String, Object>> future : futures) {
                Map<String, Object> instanceMetrics = future.get();
                if (instanceMetrics != null) {
                    merged.putAll(instanceMetrics);
                }
            }

            LOG.infof("Retrieved metrics for %d warehouses", merged.size());
            return merged;

        } catch (Exception e) {
            LOG.error("Error querying all warehouse metrics", e);
            throw new RuntimeException("Failed to query warehouse metrics", e);
        }
    }

    // ===========================================
    // State Store 6: hourly-delivery-performance
    // ===========================================

    /**
     * Get hourly performance for a specific warehouse (current window).
     */
    public List<Map<String, Object>> getHourlyPerformance(String warehouseId) {
        LOG.debugf("Querying hourly performance for: %s", warehouseId);

        try {
            List<StreamsInstanceMetadata> instances = discoveryService.discoverInstances(HOURLY_PERFORMANCE_STORE);

            if (instances.isEmpty()) {
                return new ArrayList<>();
            }

            List<CompletableFuture<List<Map<String, Object>>>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                    () -> queryJsonArray(instance, "/state/hourly-delivery-performance/" + warehouseId),
                    executorService
                ))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Map<String, Object>> allStats = new ArrayList<>();
            for (CompletableFuture<List<Map<String, Object>>> future : futures) {
                allStats.addAll(future.get());
            }

            return allStats;

        } catch (Exception e) {
            LOG.errorf(e, "Error querying hourly performance for: %s", warehouseId);
            throw new RuntimeException("Failed to query hourly performance", e);
        }
    }

    /**
     * Get hourly performance for all warehouses (current window).
     */
    public Map<String, Object> getAllHourlyPerformance() {
        LOG.debug("Querying all hourly performance");

        try {
            List<StreamsInstanceMetadata> instances = discoveryService.discoverInstances(HOURLY_PERFORMANCE_STORE);

            if (instances.isEmpty()) {
                return new HashMap<>();
            }

            List<CompletableFuture<Map<String, Object>>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                    () -> queryJsonObject(instance, "/state/hourly-delivery-performance"),
                    executorService
                ))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            Map<String, Object> merged = new HashMap<>();
            for (CompletableFuture<Map<String, Object>> future : futures) {
                Map<String, Object> instanceStats = future.get();
                if (instanceStats != null) {
                    merged.putAll(instanceStats);
                }
            }

            LOG.infof("Retrieved performance data for %d warehouses", merged.size());
            return merged;

        } catch (Exception e) {
            LOG.error("Error querying all hourly performance", e);
            throw new RuntimeException("Failed to query hourly performance", e);
        }
    }

    // ===========================================
    // State Store 7: order-current-state
    // ===========================================

    /**
     * Get current state for a specific order.
     */
    public Map<String, Object> getOrderState(String orderId) {
        LOG.debugf("Querying order state for: %s", orderId);

        try {
            StreamsInstanceMetadata instance = discoveryService.findInstanceForKey(ORDER_STATE_STORE, orderId);

            if (instance == null) {
                LOG.warnf("No instance found for order: %s", orderId);
                return null;
            }

            return queryJsonObject(instance, "/state/order-current-state/" + orderId);

        } catch (Exception e) {
            LOG.errorf(e, "Error querying order state for: %s", orderId);
            throw new RuntimeException("Failed to query order state", e);
        }
    }

    /**
     * Get current state for all orders.
     */
    public List<Map<String, Object>> getAllOrderStates() {
        LOG.debug("Querying all order states");

        try {
            List<StreamsInstanceMetadata> instances = discoveryService.discoverInstances(ORDER_STATE_STORE);

            if (instances.isEmpty()) {
                return new ArrayList<>();
            }

            List<CompletableFuture<List<Map<String, Object>>>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                    () -> queryJsonArray(instance, "/state/order-current-state"),
                    executorService
                ))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Map<String, Object>> allOrders = new ArrayList<>();
            for (CompletableFuture<List<Map<String, Object>>> future : futures) {
                allOrders.addAll(future.get());
            }

            LOG.infof("Retrieved %d order states", allOrders.size());
            return allOrders;

        } catch (Exception e) {
            LOG.error("Error querying all order states", e);
            throw new RuntimeException("Failed to query order states", e);
        }
    }

    // ===========================================
    // State Store 8: orders-by-customer
    // ===========================================

    /**
     * Get order stats for a specific customer.
     */
    public Map<String, Object> getCustomerOrderStats(String customerId) {
        LOG.debugf("Querying order stats for customer: %s", customerId);

        try {
            StreamsInstanceMetadata instance = discoveryService.findInstanceForKey(ORDERS_BY_CUSTOMER_STORE, customerId);

            if (instance == null) {
                LOG.warnf("No instance found for customer orders: %s", customerId);
                return null;
            }

            return queryJsonObject(instance, "/state/orders-by-customer/" + customerId);

        } catch (Exception e) {
            LOG.errorf(e, "Error querying customer order stats for: %s", customerId);
            throw new RuntimeException("Failed to query customer order stats", e);
        }
    }

    /**
     * Get order stats for all customers.
     */
    public List<Map<String, Object>> getAllCustomerOrderStats() {
        LOG.debug("Querying all customer order stats");

        try {
            List<StreamsInstanceMetadata> instances = discoveryService.discoverInstances(ORDERS_BY_CUSTOMER_STORE);

            if (instances.isEmpty()) {
                return new ArrayList<>();
            }

            List<CompletableFuture<List<Map<String, Object>>>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                    () -> queryJsonArray(instance, "/state/orders-by-customer"),
                    executorService
                ))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Map<String, Object>> allStats = new ArrayList<>();
            for (CompletableFuture<List<Map<String, Object>>> future : futures) {
                allStats.addAll(future.get());
            }

            LOG.infof("Retrieved order stats for %d customers", allStats.size());
            return allStats;

        } catch (Exception e) {
            LOG.error("Error querying all customer order stats", e);
            throw new RuntimeException("Failed to query customer order stats", e);
        }
    }

    // ===========================================
    // State Store 9: order-sla-tracking
    // ===========================================

    /**
     * Get SLA tracking details for a specific order.
     */
    public Map<String, Object> getOrderSLATracking(String orderId) {
        LOG.debugf("Querying SLA tracking for order: %s", orderId);

        try {
            StreamsInstanceMetadata instance = discoveryService.findInstanceForKey(ORDER_SLA_TRACKING_STORE, orderId);

            if (instance == null) {
                LOG.warnf("No instance found for order SLA tracking: %s", orderId);
                return null;
            }

            return queryJsonObject(instance, "/state/order-sla-tracking/" + orderId);

        } catch (Exception e) {
            LOG.errorf(e, "Error querying SLA tracking for order: %s", orderId);
            throw new RuntimeException("Failed to query order SLA tracking", e);
        }
    }

    /**
     * Get all orders at SLA risk.
     */
    public List<Map<String, Object>> getOrdersAtSLARisk() {
        LOG.debug("Querying all orders at SLA risk");

        try {
            List<StreamsInstanceMetadata> instances = discoveryService.discoverInstances(ORDER_SLA_TRACKING_STORE);

            if (instances.isEmpty()) {
                return new ArrayList<>();
            }

            List<CompletableFuture<List<Map<String, Object>>>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                    () -> queryJsonArray(instance, "/state/order-sla-tracking"),
                    executorService
                ))
                .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<Map<String, Object>> allAtRisk = new ArrayList<>();
            for (CompletableFuture<List<Map<String, Object>>> future : futures) {
                allAtRisk.addAll(future.get());
            }

            LOG.infof("Retrieved %d orders at SLA risk", allAtRisk.size());
            return allAtRisk;

        } catch (Exception e) {
            LOG.error("Error querying orders at SLA risk", e);
            throw new RuntimeException("Failed to query orders at SLA risk", e);
        }
    }

    // ===========================================
    // Helper Methods
    // ===========================================

    private Map<String, Long> queryJsonNumberMap(StreamsInstanceMetadata instance, String path) {
        String url = instance.getUrl() + path;
        LOG.debugf("Querying instance: %s", url);

        try {
            JsonObject json = client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .get(JsonObject.class);

            Map<String, Long> counts = new HashMap<>();
            for (String key : json.keySet()) {
                JsonNumber value = json.getJsonNumber(key);
                counts.put(key, value != null ? value.longValue() : 0L);
            }

            LOG.debugf("Instance %s returned counts: %s", instance, counts);
            return counts;

        } catch (Exception e) {
            LOG.warnf("Failed to query instance %s: %s", instance, e.getMessage());
            return new HashMap<>();
        }
    }

    private Map<String, Object> queryJsonObject(StreamsInstanceMetadata instance, String path) {
        String url = instance.getUrl() + path;
        LOG.debugf("Querying instance: %s", url);

        try {
            JsonObject json = client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .get(JsonObject.class);

            return jsonObjectToMap(json);

        } catch (Exception e) {
            LOG.warnf("Failed to query instance %s: %s", instance, e.getMessage());
            return new HashMap<>();
        }
    }

    private List<Map<String, Object>> queryJsonArray(StreamsInstanceMetadata instance, String path) {
        String url = instance.getUrl() + path;
        LOG.debugf("Querying instance: %s", url);

        try {
            JsonArray jsonArray = client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .get(JsonArray.class);

            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonValue value : jsonArray) {
                if (value instanceof JsonObject jsonObject) {
                    result.add(jsonObjectToMap(jsonObject));
                }
            }

            LOG.debugf("Instance %s returned %d items", instance, result.size());
            return result;

        } catch (Exception e) {
            LOG.warnf("Failed to query instance %s: %s", instance, e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, Object> jsonObjectToMap(JsonObject json) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : json.keySet()) {
            JsonValue value = json.get(key);
            map.put(key, convertJsonValue(value));
        }
        return map;
    }

    private Object convertJsonValue(JsonValue value) {
        return switch (value.getValueType()) {
            case STRING -> ((jakarta.json.JsonString) value).getString();
            case NUMBER -> ((jakarta.json.JsonNumber) value).numberValue();
            case TRUE -> true;
            case FALSE -> false;
            case NULL -> null;
            case ARRAY -> {
                List<Object> list = new ArrayList<>();
                for (JsonValue v : (JsonArray) value) {
                    list.add(convertJsonValue(v));
                }
                yield list;
            }
            case OBJECT -> jsonObjectToMap((JsonObject) value);
        };
    }
}
