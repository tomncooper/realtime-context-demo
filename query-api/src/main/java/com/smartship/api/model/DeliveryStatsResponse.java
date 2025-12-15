package com.smartship.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for hourly delivery performance from Kafka Streams windowed state store.
 */
public record DeliveryStatsResponse(
    @JsonProperty("warehouse_id") String warehouseId,
    @JsonProperty("window_start") long windowStart,
    @JsonProperty("window_end") long windowEnd,
    @JsonProperty("on_time_count") long onTimeCount,
    @JsonProperty("late_count") long lateCount,
    @JsonProperty("total_delivered") long totalDelivered,
    @JsonProperty("on_time_percentage") double onTimePercentage,
    @JsonProperty("total_delivery_time_ms") long totalDeliveryTimeMs,
    @JsonProperty("avg_delivery_time_ms") double avgDeliveryTimeMs,
    @JsonProperty("last_updated") long lastUpdated
) {}
