package com.smartship.streams.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartship.logistics.events.ShipmentEvent;
import com.smartship.logistics.events.ShipmentEventType;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * State store value representing aggregated shipment statistics per customer.
 * Updated from shipment.events topic.
 */
public record CustomerShipmentStats(
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("total_shipments") long totalShipments,
    @JsonProperty("in_transit_count") long inTransitCount,
    @JsonProperty("delivered_count") long deliveredCount,
    @JsonProperty("late_count") long lateCount,
    @JsonProperty("cancelled_count") long cancelledCount,
    @JsonProperty("exception_count") long exceptionCount,
    @JsonIgnore long lastUpdated
) {
    private static final DateTimeFormatter ISO_FORMATTER =
        DateTimeFormatter.ISO_INSTANT;

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
     * Create an empty stats record for a customer.
     */
    public static CustomerShipmentStats empty() {
        return new CustomerShipmentStats(null, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Update stats based on a shipment event.
     * Increments appropriate counters based on event type.
     */
    public CustomerShipmentStats update(ShipmentEvent event) {
        String cid = event.getCustomerId();
        long total = this.totalShipments;
        long inTransit = this.inTransitCount;
        long delivered = this.deliveredCount;
        long late = this.lateCount;
        long cancelled = this.cancelledCount;
        long exception = this.exceptionCount;

        ShipmentEventType eventType = event.getEventType();

        switch (eventType) {
            case CREATED -> total++;
            case IN_TRANSIT -> inTransit++;
            case DELIVERED -> {
                delivered++;
                if (inTransit > 0) inTransit--;
            }
            case CANCELLED -> {
                cancelled++;
                if (inTransit > 0) inTransit--;
            }
            case EXCEPTION -> exception++;
            default -> { /* Other states don't change counts */ }
        }

        // Check if shipment is late (expected_delivery has passed but not delivered)
        if (eventType != ShipmentEventType.DELIVERED &&
            eventType != ShipmentEventType.CANCELLED &&
            event.getExpectedDelivery() < System.currentTimeMillis()) {
            late++;
        }

        return new CustomerShipmentStats(
            cid, total, inTransit, delivered, late, cancelled, exception,
            event.getTimestamp()
        );
    }
}
