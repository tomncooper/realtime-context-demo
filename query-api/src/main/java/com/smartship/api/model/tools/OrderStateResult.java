package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Result record for individual order state.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderStateResult(
    @JsonProperty("order_id") String orderId,
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("status") String status,
    @JsonProperty("priority") String priority,
    @JsonProperty("shipment_ids") List<String> shipmentIds,
    @JsonProperty("created_at") Long createdAt,
    @JsonProperty("last_updated") Long lastUpdated,
    @JsonProperty("message") String message
) {
    @SuppressWarnings("unchecked")
    public static OrderStateResult of(String orderId, Map<String, Object> state) {
        return new OrderStateResult(
            orderId,
            toString(state.get("customer_id")),
            toString(state.get("status")),
            toString(state.get("priority")),
            (List<String>) state.get("shipment_ids"),
            toLong(state.get("created_at")),
            toLong(state.get("last_updated")),
            null
        );
    }

    public static OrderStateResult notFound(String orderId) {
        return new OrderStateResult(
            orderId, null, null, null, null, null, null,
            "No order found with ID: " + orderId
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

    private static String toString(Object value) {
        return value != null ? value.toString() : null;
    }
}
