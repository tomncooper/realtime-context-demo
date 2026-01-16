package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Result record for customer order statistics.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomerOrderStatsResult(
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("total_orders") Long totalOrders,
    @JsonProperty("pending") Long pending,
    @JsonProperty("confirmed") Long confirmed,
    @JsonProperty("shipped") Long shipped,
    @JsonProperty("delivered") Long delivered,
    @JsonProperty("at_risk") Long atRisk,
    @JsonProperty("message") String message
) {
    public static CustomerOrderStatsResult of(String customerId, Map<String, Object> stats) {
        return new CustomerOrderStatsResult(
            customerId,
            toLong(stats.get("total_orders")),
            toLong(stats.get("pending")),
            toLong(stats.get("confirmed")),
            toLong(stats.get("shipped")),
            toLong(stats.get("delivered")),
            toLong(stats.get("at_risk")),
            null
        );
    }

    public static CustomerOrderStatsResult notFound(String customerId) {
        return new CustomerOrderStatsResult(
            customerId, null, null, null, null, null, null,
            "No order data found for customer " + customerId + ". The customer may not have any orders yet."
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
