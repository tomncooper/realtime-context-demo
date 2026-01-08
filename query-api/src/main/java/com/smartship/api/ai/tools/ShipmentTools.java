package com.smartship.api.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartship.api.KafkaStreamsQueryService;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * LangChain4j tools for querying shipment data from Kafka Streams state stores.
 *
 * <p>These tools wrap the KafkaStreamsQueryService methods and return JSON strings
 * that the LLM can understand and use to answer user questions.</p>
 */
@ApplicationScoped
public class ShipmentTools {

    private static final Logger LOG = Logger.getLogger(ShipmentTools.class);

    @Inject
    KafkaStreamsQueryService streamsQueryService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Get the count of shipments for each status (CREATED, IN_TRANSIT, DELIVERED, etc).
     * This gives an overview of all active shipments in the system grouped by their current status.
     */
    @Tool("Get the count of shipments for each status (CREATED, PICKED, PACKED, DISPATCHED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, CANCELLED). Returns a JSON object with status counts.")
    public String getShipmentStatusCounts() {
        LOG.info("Tool called: getShipmentStatusCounts");
        try {
            Map<String, Object> counts = streamsQueryService.getAllStatusCounts();

            if (counts == null || counts.isEmpty()) {
                return "{\"message\": \"No shipment data available yet. The system may still be initializing.\"}";
            }

            return toJson(Map.of(
                "status_counts", counts,
                "total_shipments", counts.values().stream()
                    .filter(v -> v instanceof Number)
                    .mapToLong(v -> ((Number) v).longValue())
                    .sum()
            ));
        } catch (Exception e) {
            LOG.errorf(e, "Error getting shipment status counts");
            return "{\"error\": \"Failed to retrieve shipment status counts: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get all shipments that are currently late (past their expected delivery time).
     * Returns details about each late shipment including customer, SLA, and delay information.
     */
    @Tool("Get all shipments that are currently late (past their expected delivery time). Returns a list of late shipments with customer ID, expected delivery, current status, and delay duration.")
    public String getLateShipments() {
        LOG.info("Tool called: getLateShipments");
        try {
            List<Map<String, Object>> lateShipments = streamsQueryService.getAllLateShipments();

            if (lateShipments == null || lateShipments.isEmpty()) {
                return "{\"message\": \"No late shipments found. All shipments are on track.\", \"count\": 0}";
            }

            return toJson(Map.of(
                "count", lateShipments.size(),
                "late_shipments", lateShipments
            ));
        } catch (Exception e) {
            LOG.errorf(e, "Error getting late shipments");
            return "{\"error\": \"Failed to retrieve late shipments: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get shipment statistics for a specific customer.
     * The customer ID format is CUST-XXXX (e.g., CUST-0001, CUST-0050, CUST-0200).
     */
    @Tool("Get shipment statistics for a specific customer by their ID. Customer ID format is CUST-XXXX (e.g., CUST-0001). Returns total shipments, in-transit count, delivered count, and late count for that customer.")
    public String getCustomerShipmentStats(String customerId) {
        LOG.infof("Tool called: getCustomerShipmentStats for customer: %s", customerId);

        if (customerId == null || customerId.isBlank()) {
            return "{\"error\": \"Customer ID is required. Format: CUST-XXXX (e.g., CUST-0001)\"}";
        }

        // Normalize customer ID format
        String normalizedId = customerId.toUpperCase().trim();
        if (!normalizedId.startsWith("CUST-")) {
            normalizedId = "CUST-" + normalizedId;
        }

        try {
            Map<String, Object> stats = streamsQueryService.getCustomerShipmentStats(normalizedId);

            if (stats == null || stats.isEmpty()) {
                return "{\"message\": \"No shipment data found for customer " + normalizedId + ". The customer may not have any shipments yet.\"}";
            }

            stats.put("customer_id", normalizedId);
            return toJson(stats);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting customer shipment stats for: %s", normalizedId);
            return "{\"error\": \"Failed to retrieve shipment stats for customer " + normalizedId + ": " + e.getMessage() + "\"}";
        }
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
