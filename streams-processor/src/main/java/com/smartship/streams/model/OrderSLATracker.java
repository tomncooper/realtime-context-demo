package com.smartship.streams.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartship.logistics.events.OrderStatus;
import com.smartship.logistics.events.OrderStatusType;

/**
 * State store value for tracking orders at risk of SLA breach.
 * Orders are considered at risk when within 60 minutes of SLA deadline.
 */
public record OrderSLATracker(
    @JsonProperty("order_id") String orderId,
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("priority") String priority,
    @JsonProperty("current_status") String currentStatus,
    @JsonProperty("sla_timestamp") long slaTimestamp,
    @JsonProperty("time_to_sla_minutes") long timeToSlaMinutes,
    @JsonProperty("is_at_risk") boolean isAtRisk,
    @JsonProperty("is_breached") boolean isBreached,
    @JsonProperty("shipment_count") int shipmentCount,
    @JsonProperty("last_updated") long lastUpdated
) {
    // SLA risk threshold: 60 minutes before deadline
    public static final long SLA_RISK_THRESHOLD_MINUTES = 60;

    /**
     * Create a tracker from an OrderStatus event.
     */
    public static OrderSLATracker from(OrderStatus event) {
        long now = System.currentTimeMillis();
        long sla = event.getSlaTimestamp();
        long minutesToSla = (sla - now) / 60000;

        OrderStatusType status = event.getStatus();
        boolean isTerminal = status == OrderStatusType.DELIVERED ||
                            status == OrderStatusType.CANCELLED ||
                            status == OrderStatusType.RETURNED;

        boolean atRisk = !isTerminal && minutesToSla <= SLA_RISK_THRESHOLD_MINUTES && minutesToSla >= 0;
        boolean breached = !isTerminal && minutesToSla < 0;

        return new OrderSLATracker(
            event.getOrderId(),
            event.getCustomerId(),
            event.getPriority().toString(),
            status.toString(),
            sla,
            minutesToSla,
            atRisk,
            breached,
            event.getShipmentIds().size(),
            event.getTimestamp()
        );
    }

    /**
     * Check if an order event should be tracked (at risk or breached).
     */
    public static boolean shouldTrack(OrderStatus event) {
        OrderStatusType status = event.getStatus();

        // Don't track terminal states
        if (status == OrderStatusType.DELIVERED ||
            status == OrderStatusType.CANCELLED ||
            status == OrderStatusType.RETURNED) {
            return false;
        }

        long now = System.currentTimeMillis();
        long sla = event.getSlaTimestamp();
        long minutesToSla = (sla - now) / 60000;

        // Track if at risk (within threshold) or already breached
        return minutesToSla <= SLA_RISK_THRESHOLD_MINUTES;
    }

    /**
     * Get severity level for display.
     */
    public String getSeverity() {
        if (isBreached) {
            return "CRITICAL";
        } else if (timeToSlaMinutes <= 30) {
            return "HIGH";
        } else {
            return "MEDIUM";
        }
    }
}
