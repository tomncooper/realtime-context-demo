package com.smartship.api.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartship.api.model.tools.CustomerShipmentStatsResult;
import com.smartship.api.model.tools.LateShipmentsResult;
import com.smartship.api.model.tools.ShipmentStatusResult;
import com.smartship.api.services.ToolOperationsService;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * LangChain4j tools for querying shipment data from Kafka Streams state stores.
 *
 * <p>These tools wrap the ToolOperationsService methods and return JSON strings
 * that the LLM can understand and use to answer user questions.</p>
 */
@ApplicationScoped
public class ShipmentTools {

    private static final Logger LOG = Logger.getLogger(ShipmentTools.class);

    @Inject
    ToolOperationsService operations;

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
            ShipmentStatusResult result = operations.getShipmentStatusCounts();
            return toJson(result);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting shipment status counts");
            return "{\"error\": \"Failed to retrieve shipment status counts: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get shipments that are currently late (past their expected delivery time).
     * Returns a summary and the top late shipments to keep the response size manageable.
     */
    @Tool("Get shipments that are currently late (delayed past expected delivery). Returns total count and details of the top 10 most delayed shipments.")
    public String getLateShipments() {
        LOG.info("Tool called: getLateShipments");
        try {
            LateShipmentsResult result = operations.getLateShipments();
            return toJson(result);
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

        try {
            CustomerShipmentStatsResult result = operations.getCustomerShipmentStats(customerId);
            return toJson(result);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting customer shipment stats for: %s", customerId);
            return "{\"error\": \"Failed to retrieve shipment stats for customer " + customerId + ": " + e.getMessage() + "\"}";
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
