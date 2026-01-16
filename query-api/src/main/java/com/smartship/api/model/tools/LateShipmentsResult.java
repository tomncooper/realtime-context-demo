package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Result record for late shipments query.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LateShipmentsResult(
    @JsonProperty("total_late_shipments") int totalLateShipments,
    @JsonProperty("showing") int showing,
    @JsonProperty("summary") String summary,
    @JsonProperty("late_shipments") List<Map<String, Object>> lateShipments,
    @JsonProperty("note") String note,
    @JsonProperty("message") String message
) {
    public static LateShipmentsResult empty() {
        return new LateShipmentsResult(
            0, 0, null, List.of(), null,
            "No late shipments found. All shipments are on track."
        );
    }

    public static LateShipmentsResult of(List<Map<String, Object>> allLateShipments, int limit) {
        int total = allLateShipments.size();
        List<Map<String, Object>> limited = allLateShipments.stream().limit(limit).toList();
        String note = total > limit
            ? String.format("Showing top %d of %d late shipments. Use the API directly for full list.", limit, total)
            : null;
        return new LateShipmentsResult(
            total,
            limited.size(),
            String.format("There are %d shipments currently delayed.", total),
            limited,
            note,
            null
        );
    }
}
