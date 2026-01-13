package com.smartship.streams.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartship.logistics.events.OrderStatus;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * State store value representing the current state of an order.
 * Updated from order.status topic.
 */
public record OrderState(
    @JsonProperty("order_id") String orderId,
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("status") String status,
    @JsonProperty("priority") String priority,
    @JsonProperty("shipment_ids") List<String> shipmentIds,
    @JsonProperty("total_items") int totalItems,
    @JsonProperty("sla_timestamp") long slaTimestamp,
    @JsonProperty("created_at") long createdAt,
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
     * Create an OrderState from an OrderStatus event.
     */
    public static OrderState from(OrderStatus event) {
        return new OrderState(
            event.getOrderId(),
            event.getCustomerId(),
            event.getStatus().toString(),
            event.getPriority().toString(),
            new ArrayList<>(event.getShipmentIds()),
            event.getTotalItems(),
            event.getSlaTimestamp(),
            event.getTimestamp(),  // First event time as created time
            event.getTimestamp()
        );
    }

    /**
     * Update state with a new OrderStatus event.
     * Preserves created_at from the original state.
     */
    public OrderState update(OrderStatus event) {
        return new OrderState(
            event.getOrderId(),
            event.getCustomerId(),
            event.getStatus().toString(),
            event.getPriority().toString(),
            new ArrayList<>(event.getShipmentIds()),
            event.getTotalItems(),
            event.getSlaTimestamp(),
            this.createdAt,  // Preserve original creation time
            event.getTimestamp()
        );
    }

    /**
     * Check if order is in a terminal state.
     */
    public boolean isTerminal() {
        return "DELIVERED".equals(status) ||
               "CANCELLED".equals(status) ||
               "RETURNED".equals(status);
    }
}
