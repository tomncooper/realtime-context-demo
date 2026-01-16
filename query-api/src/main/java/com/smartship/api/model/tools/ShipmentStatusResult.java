package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Result record for shipment status counts across all statuses.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShipmentStatusResult(
    @JsonProperty("status_counts") Map<String, Long> statusCounts,
    @JsonProperty("total_shipments") long totalShipments,
    @JsonProperty("message") String message
) {
    public static ShipmentStatusResult empty() {
        return new ShipmentStatusResult(
            Map.of(),
            0,
            "No shipment data available yet. The system may still be initializing."
        );
    }

    public static ShipmentStatusResult of(Map<String, Long> statusCounts) {
        long total = statusCounts.values().stream().mapToLong(Long::longValue).sum();
        return new ShipmentStatusResult(statusCounts, total, null);
    }
}
