package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Result record for warehouse performance metrics.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WarehousePerformanceResult(
    @JsonProperty("warehouse_id") String warehouseId,
    @JsonProperty("window_count") int windowCount,
    @JsonProperty("window_size_minutes") int windowSizeMinutes,
    @JsonProperty("windows") List<Map<String, Object>> windows,
    @JsonProperty("message") String message
) {
    public static WarehousePerformanceResult empty(String warehouseId) {
        return new WarehousePerformanceResult(
            warehouseId, 0, 60, List.of(),
            "No performance data available for warehouse " + warehouseId
        );
    }

    public static WarehousePerformanceResult of(String warehouseId, List<Map<String, Object>> windows) {
        return new WarehousePerformanceResult(
            warehouseId,
            windows.size(),
            60,
            windows,
            null
        );
    }
}
