package com.smartship.streams.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartship.logistics.events.ShipmentEvent;

import java.time.Duration;
import java.time.Instant;

/**
 * State store value representing a late shipment with details.
 * A shipment is considered late if it exceeds expected_delivery + 30 min grace period.
 */
public record LateShipmentDetails(
    @JsonProperty("shipment_id") String shipmentId,
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("warehouse_id") String warehouseId,
    @JsonProperty("expected_delivery") long expectedDelivery,
    @JsonProperty("current_status") String currentStatus,
    @JsonProperty("destination_city") String destinationCity,
    @JsonProperty("destination_country") String destinationCountry,
    @JsonProperty("delay_minutes") long delayMinutes,
    @JsonProperty("last_updated") long lastUpdated
) {
    /**
     * 30-minute grace period before marking a shipment as late.
     */
    public static final Duration LATE_GRACE_PERIOD = Duration.ofMinutes(30);

    /**
     * Check if a shipment event represents a late shipment.
     * Returns true if:
     * - The shipment is not yet delivered or cancelled
     * - Current time exceeds expected_delivery + 30 minute grace period
     */
    public static boolean isLate(ShipmentEvent event) {
        // expectedDelivery is a primitive long, so check for 0 (unset)
        if (event.getExpectedDelivery() == 0) {
            return false;
        }

        String status = event.getEventType().toString();
        if ("DELIVERED".equals(status) || "CANCELLED".equals(status)) {
            return false;
        }

        Instant expected = Instant.ofEpochMilli(event.getExpectedDelivery());
        Instant lateThreshold = expected.plus(LATE_GRACE_PERIOD);
        return Instant.now().isAfter(lateThreshold);
    }

    /**
     * Create LateShipmentDetails from a ShipmentEvent.
     */
    public static LateShipmentDetails from(ShipmentEvent event) {
        long now = System.currentTimeMillis();
        long expected = event.getExpectedDelivery();
        long delayMs = now - expected - LATE_GRACE_PERIOD.toMillis();
        long delayMinutes = Math.max(0, delayMs / 60000);

        return new LateShipmentDetails(
            event.getShipmentId(),
            event.getCustomerId(),
            event.getWarehouseId(),
            expected,
            event.getEventType().toString(),
            event.getDestinationCity(),
            event.getDestinationCountry(),
            delayMinutes,
            event.getTimestamp()
        );
    }

    /**
     * Update with a newer event for the same shipment.
     */
    public LateShipmentDetails updateWith(ShipmentEvent event) {
        return from(event);
    }
}
