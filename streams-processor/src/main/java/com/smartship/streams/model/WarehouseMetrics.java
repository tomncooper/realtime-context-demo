package com.smartship.streams.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartship.logistics.events.WarehouseOperation;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * State store value representing real-time warehouse metrics.
 * Aggregated from warehouse.operations in 15-minute tumbling windows.
 */
public record WarehouseMetrics(
    @JsonProperty("warehouse_id") String warehouseId,
    @JsonProperty("window_start") long windowStart,
    @JsonProperty("window_end") long windowEnd,
    @JsonProperty("operation_counts") Map<String, Long> operationCounts,
    @JsonProperty("total_operations") long totalOperations,
    @JsonProperty("total_duration_seconds") long totalDurationSeconds,
    @JsonProperty("avg_duration_seconds") double avgDurationSeconds,
    @JsonProperty("error_count") long errorCount,
    @JsonProperty("total_items_processed") long totalItemsProcessed,
    @JsonIgnore long lastUpdated
) {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    /**
     * Returns the last updated timestamp in ISO 8601 format.
     */
    @JsonProperty("last_updated")
    public String lastUpdatedIso() {
        if (lastUpdated == 0) {
            return null;
        }
        return Instant.ofEpochMilli(lastUpdated).atOffset(ZoneOffset.UTC).format(ISO_FORMATTER);
    }

    /**
     * Create empty metrics for initial aggregation.
     */
    public static WarehouseMetrics empty() {
        return new WarehouseMetrics(
            null, 0, 0, new HashMap<>(), 0, 0, 0.0, 0, 0, 0
        );
    }

    /**
     * Update metrics with a new warehouse operation.
     */
    public WarehouseMetrics update(WarehouseOperation operation) {
        String wid = operation.getWarehouseId();
        String opType = operation.getOperationType().toString();

        // Update operation counts
        Map<String, Long> newCounts = new HashMap<>(this.operationCounts);
        newCounts.merge(opType, 1L, Long::sum);

        long newTotal = this.totalOperations + 1;
        long newDuration = this.totalDurationSeconds + operation.getDurationSeconds();
        double newAvg = (double) newDuration / newTotal;

        // Count errors (non-empty errors list)
        long newErrorCount = this.errorCount;
        if (operation.getErrors() != null && !operation.getErrors().isEmpty()) {
            newErrorCount++;
        }

        long newItems = this.totalItemsProcessed + operation.getQuantity();

        return new WarehouseMetrics(
            wid,
            this.windowStart,
            this.windowEnd,
            newCounts,
            newTotal,
            newDuration,
            newAvg,
            newErrorCount,
            newItems,
            operation.getTimestamp()
        );
    }

    /**
     * Create metrics with window boundaries set.
     */
    public WarehouseMetrics withWindow(long start, long end) {
        return new WarehouseMetrics(
            this.warehouseId, start, end,
            this.operationCounts, this.totalOperations,
            this.totalDurationSeconds, this.avgDurationSeconds,
            this.errorCount, this.totalItemsProcessed, this.lastUpdated
        );
    }
}
