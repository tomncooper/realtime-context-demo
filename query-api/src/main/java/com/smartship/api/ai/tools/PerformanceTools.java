package com.smartship.api.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartship.api.KafkaStreamsQueryService;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j tools for querying performance metrics from Kafka Streams
 * windowed state stores.
 *
 * <p>These tools provide warehouse-level performance metrics including
 * hourly delivery performance and real-time operational metrics.</p>
 */
@ApplicationScoped
public class PerformanceTools {

    private static final Logger LOG = Logger.getLogger(PerformanceTools.class);

    @Inject
    KafkaStreamsQueryService streamsQueryService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Get hourly delivery performance for a specific warehouse.
     */
    @Tool("Get hourly delivery performance for a specific warehouse. Warehouse ID format is WH-XXX (e.g., WH-RTM for Rotterdam, WH-FRA for Frankfurt, WH-BCN for Barcelona, WH-WAW for Warsaw, WH-STO for Stockholm). Returns on-time delivery rate, late deliveries, and average delivery times for recent hours.")
    public String getWarehousePerformance(String warehouseId) {
        LOG.infof("Tool called: getWarehousePerformance for: %s", warehouseId);

        if (warehouseId == null || warehouseId.isBlank()) {
            return "{\"error\": \"Warehouse ID is required. Format: WH-XXX (e.g., WH-RTM)\"}";
        }

        String normalizedId = normalizeWarehouseId(warehouseId);

        try {
            List<Map<String, Object>> performance = streamsQueryService.getHourlyPerformance(normalizedId);

            if (performance == null || performance.isEmpty()) {
                return toJson(Map.of(
                    "message", "No hourly performance data available for warehouse " + normalizedId + ". Data may still be accumulating.",
                    "warehouse_id", normalizedId,
                    "windows", 0
                ));
            }

            return toJson(Map.of(
                "warehouse_id", normalizedId,
                "windows", performance.size(),
                "hourly_performance", performance,
                "note", "Each window represents a 1-hour period with delivery statistics"
            ));

        } catch (Exception e) {
            LOG.errorf(e, "Error getting warehouse performance for: %s", normalizedId);
            return "{\"error\": \"Failed to retrieve performance for warehouse " + normalizedId + ": " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get real-time operational metrics for a specific warehouse.
     */
    @Tool("Get real-time operational metrics for a specific warehouse including operation counts (picks, packs, loads), throughput rates, and error rates. Uses 15-minute tumbling windows for recent activity. Warehouse ID format is WH-XXX (e.g., WH-RTM).")
    public String getWarehouseMetrics(String warehouseId) {
        LOG.infof("Tool called: getWarehouseMetrics for: %s", warehouseId);

        if (warehouseId == null || warehouseId.isBlank()) {
            return "{\"error\": \"Warehouse ID is required. Format: WH-XXX (e.g., WH-RTM)\"}";
        }

        String normalizedId = normalizeWarehouseId(warehouseId);

        try {
            List<Map<String, Object>> metrics = streamsQueryService.getWarehouseMetrics(normalizedId);

            if (metrics == null || metrics.isEmpty()) {
                return toJson(Map.of(
                    "message", "No real-time metrics available for warehouse " + normalizedId + ". Data may still be accumulating.",
                    "warehouse_id", normalizedId,
                    "windows", 0
                ));
            }

            return toJson(Map.of(
                "warehouse_id", normalizedId,
                "windows", metrics.size(),
                "realtime_metrics", metrics,
                "note", "Each window represents a 15-minute period with operational metrics"
            ));

        } catch (Exception e) {
            LOG.errorf(e, "Error getting warehouse metrics for: %s", normalizedId);
            return "{\"error\": \"Failed to retrieve metrics for warehouse " + normalizedId + ": " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get performance summary across all warehouses.
     */
    @Tool("Get performance summary across all warehouses. Returns aggregated hourly delivery performance data for the entire network. Useful for comparing warehouse performance or getting a network-wide overview.")
    public String getAllPerformanceMetrics() {
        LOG.info("Tool called: getAllPerformanceMetrics");

        try {
            Map<String, Object> allPerformance = streamsQueryService.getAllHourlyPerformance();
            Map<String, Object> allMetrics = streamsQueryService.getAllWarehouseMetrics();

            if ((allPerformance == null || allPerformance.isEmpty()) &&
                (allMetrics == null || allMetrics.isEmpty())) {
                return "{\"message\": \"No performance data available. The system may still be initializing.\"}";
            }

            Map<String, Object> result = new HashMap<>();
            result.put("hourly_performance", allPerformance != null ? allPerformance : Map.of());
            result.put("realtime_metrics", allMetrics != null ? allMetrics : Map.of());
            result.put("warehouses_with_performance_data", allPerformance != null ? allPerformance.size() : 0);
            result.put("warehouses_with_metrics_data", allMetrics != null ? allMetrics.size() : 0);

            return toJson(result);

        } catch (Exception e) {
            LOG.errorf(e, "Error getting all performance metrics");
            return "{\"error\": \"Failed to retrieve performance metrics: " + e.getMessage() + "\"}";
        }
    }

    private String normalizeWarehouseId(String warehouseId) {
        String normalized = warehouseId.toUpperCase().trim();
        if (!normalized.startsWith("WH-")) {
            normalized = "WH-" + normalized;
        }
        return normalized;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize object to JSON", e);
            return "{\"error\": \"Failed to serialize response\"}";
        }
    }
}
