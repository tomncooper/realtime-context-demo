package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result record for shipment count by specific status.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShipmentByStatusResult(
    @JsonProperty("status") String status,
    @JsonProperty("count") long count,
    @JsonProperty("message") String message
) {
    public static ShipmentByStatusResult of(String status, long count) {
        return new ShipmentByStatusResult(status, count, null);
    }

    public static ShipmentByStatusResult notFound(String status) {
        return new ShipmentByStatusResult(
            status, 0,
            "No shipments found with status: " + status
        );
    }
}
