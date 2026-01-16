package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Result record for customer shipment statistics.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomerShipmentStatsResult(
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("total_shipments") Long totalShipments,
    @JsonProperty("in_transit") Long inTransit,
    @JsonProperty("delivered") Long delivered,
    @JsonProperty("exceptions") Long exceptions,
    @JsonProperty("message") String message
) {
    public static CustomerShipmentStatsResult of(String customerId, Map<String, Object> stats) {
        return new CustomerShipmentStatsResult(
            customerId,
            toLong(stats.get("total_shipments")),
            toLong(stats.get("in_transit")),
            toLong(stats.get("delivered")),
            toLong(stats.get("exceptions")),
            null
        );
    }

    public static CustomerShipmentStatsResult notFound(String customerId) {
        return new CustomerShipmentStatsResult(
            customerId, null, null, null, null,
            "No shipment data found for customer " + customerId + ". The customer may not have any shipments yet."
        );
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
