package com.smartship.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Response DTO for warehouse realtime metrics from Kafka Streams windowed state store.
 */
public record WarehouseMetricsResponse(
    @JsonProperty("warehouse_id") String warehouseId,
    @JsonProperty("window_start") long windowStart,
    @JsonProperty("window_end") long windowEnd,
    @JsonProperty("operation_counts") Map<String, Long> operationCounts,
    @JsonProperty("total_operations") long totalOperations,
    @JsonProperty("total_duration_seconds") long totalDurationSeconds,
    @JsonProperty("avg_duration_seconds") double avgDurationSeconds,
    @JsonProperty("error_count") long errorCount,
    @JsonProperty("total_items_processed") long totalItemsProcessed,
    @JsonProperty("last_updated") long lastUpdated
) {}
