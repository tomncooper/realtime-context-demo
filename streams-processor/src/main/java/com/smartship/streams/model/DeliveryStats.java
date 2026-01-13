package com.smartship.streams.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartship.logistics.events.ShipmentEvent;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * State store value representing hourly delivery performance statistics.
 * Aggregated from shipment.events (DELIVERED events) in 1-hour hopping windows.
 * Measures DISPATCHED -> DELIVERED time.
 */
public record DeliveryStats(
    @JsonProperty("warehouse_id") String warehouseId,
    @JsonProperty("window_start") long windowStart,
    @JsonProperty("window_end") long windowEnd,
    @JsonProperty("on_time_count") long onTimeCount,
    @JsonProperty("late_count") long lateCount,
    @JsonProperty("total_delivered") long totalDelivered,
    @JsonProperty("on_time_percentage") double onTimePercentage,
    @JsonProperty("total_delivery_time_ms") long totalDeliveryTimeMs,
    @JsonProperty("avg_delivery_time_ms") double avgDeliveryTimeMs,
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
     * 30-minute grace period for on-time calculation.
     */
    public static final Duration LATE_GRACE_PERIOD = Duration.ofMinutes(30);

    /**
     * Create empty stats for initial aggregation.
     */
    public static DeliveryStats empty() {
        return new DeliveryStats(null, 0, 0, 0, 0, 0, 0.0, 0, 0.0, 0);
    }

    /**
     * Record a delivery event and update statistics.
     * Note: For accurate DISPATCHED->DELIVERED timing, we'd need to track
     * dispatch times in a separate state store. For now, we use the event
     * timestamp relative to expected delivery to determine on-time status.
     */
    public DeliveryStats recordDelivery(ShipmentEvent event) {
        String wid = event.getWarehouseId();
        long deliveryTime = event.getTimestamp();
        long expected = event.getExpectedDelivery();

        // Determine if delivery was on time (within 30-min grace period)
        boolean onTime = deliveryTime <= (expected + LATE_GRACE_PERIOD.toMillis());

        long newOnTime = this.onTimeCount + (onTime ? 1 : 0);
        long newLate = this.lateCount + (onTime ? 0 : 1);
        long newTotal = this.totalDelivered + 1;
        double newOnTimePercent = newTotal > 0 ? (100.0 * newOnTime / newTotal) : 0.0;

        // For delivery time, we measure from expected to actual
        // (Ideally this would be DISPATCHED -> DELIVERED, tracked via join)
        long deliveryDuration = Math.abs(deliveryTime - expected);
        long newTotalTime = this.totalDeliveryTimeMs + deliveryDuration;
        double newAvgTime = newTotal > 0 ? (double) newTotalTime / newTotal : 0.0;

        return new DeliveryStats(
            wid,
            this.windowStart,
            this.windowEnd,
            newOnTime,
            newLate,
            newTotal,
            newOnTimePercent,
            newTotalTime,
            newAvgTime,
            deliveryTime
        );
    }

    /**
     * Create stats with window boundaries set.
     */
    public DeliveryStats withWindow(long start, long end) {
        return new DeliveryStats(
            this.warehouseId, start, end,
            this.onTimeCount, this.lateCount, this.totalDelivered,
            this.onTimePercentage, this.totalDeliveryTimeMs,
            this.avgDeliveryTimeMs, this.lastUpdated
        );
    }
}
