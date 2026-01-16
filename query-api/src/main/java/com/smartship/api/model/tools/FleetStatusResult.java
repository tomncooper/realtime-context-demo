package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Result record for fleet-wide vehicle status.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FleetStatusResult(
    @JsonProperty("count") int count,
    @JsonProperty("status_summary") Map<String, Long> statusSummary,
    @JsonProperty("vehicles") List<Map<String, Object>> vehicles,
    @JsonProperty("note") String note,
    @JsonProperty("message") String message
) {
    public static FleetStatusResult empty() {
        return new FleetStatusResult(
            0, Map.of(), List.of(), null,
            "No vehicle telemetry data available. The system may still be initializing."
        );
    }

    public static FleetStatusResult of(List<Map<String, Object>> allVehicles, Map<String, Long> statusSummary, int limit) {
        int total = allVehicles.size();
        List<Map<String, Object>> limited = allVehicles.stream().limit(limit).toList();
        String note = total > limit
            ? String.format("Showing first %d of %d vehicles", limit, total)
            : null;
        return new FleetStatusResult(total, statusSummary, limited, note, null);
    }
}
