package com.smartship.streams.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartship.logistics.events.OrderStatus;
import com.smartship.logistics.events.OrderStatusType;

/**
 * State store value representing aggregated order statistics per customer.
 * Updated from order.status topic.
 */
public record CustomerOrderStats(
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("total_orders") long totalOrders,
    @JsonProperty("pending_orders") long pendingOrders,
    @JsonProperty("shipped_orders") long shippedOrders,
    @JsonProperty("delivered_orders") long deliveredOrders,
    @JsonProperty("cancelled_orders") long cancelledOrders,
    @JsonProperty("returned_orders") long returnedOrders,
    @JsonProperty("at_risk_orders") long atRiskOrders,
    @JsonProperty("last_updated") long lastUpdated
) {
    /**
     * Create an empty stats record for a customer.
     */
    public static CustomerOrderStats empty() {
        return new CustomerOrderStats(null, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Update stats based on an order status event.
     */
    public CustomerOrderStats update(OrderStatus event) {
        String cid = event.getCustomerId();
        long total = this.totalOrders;
        long pending = this.pendingOrders;
        long shipped = this.shippedOrders;
        long delivered = this.deliveredOrders;
        long cancelled = this.cancelledOrders;
        long returned = this.returnedOrders;
        long atRisk = this.atRiskOrders;

        OrderStatusType statusType = event.getStatus();

        switch (statusType) {
            case RECEIVED -> {
                total++;
                pending++;
            }
            case VALIDATED, ALLOCATED -> {
                // Still pending, no counter change needed
                // (assuming we received RECEIVED first)
            }
            case SHIPPED -> {
                shipped++;
                if (pending > 0) pending--;
            }
            case DELIVERED -> {
                delivered++;
                if (shipped > 0) shipped--;
                // Remove from at-risk if it was at risk
                if (atRisk > 0) atRisk--;
            }
            case CANCELLED -> {
                cancelled++;
                if (pending > 0) pending--;
                else if (shipped > 0) shipped--;
                if (atRisk > 0) atRisk--;
            }
            case RETURNED -> {
                returned++;
                if (delivered > 0) delivered--;
            }
            case PARTIAL_FAILURE -> {
                // Partial failure is terminal - order had shipment exception
                cancelled++;  // Count as failed/cancelled for stats
                if (pending > 0) pending--;
                else if (shipped > 0) shipped--;
                if (atRisk > 0) atRisk--;
            }
        }

        // Check if order is at SLA risk
        if (!statusType.equals(OrderStatusType.DELIVERED) &&
            !statusType.equals(OrderStatusType.CANCELLED) &&
            !statusType.equals(OrderStatusType.RETURNED) &&
            !statusType.equals(OrderStatusType.PARTIAL_FAILURE)) {
            long now = System.currentTimeMillis();
            long sla = event.getSlaTimestamp();
            long minutesToSla = (sla - now) / 60000;
            // At risk if less than 60 minutes to SLA and not terminal
            if (minutesToSla <= 60 && minutesToSla >= 0) {
                atRisk++;
            }
        }

        return new CustomerOrderStats(
            cid, total, pending, shipped, delivered, cancelled, returned, atRisk,
            event.getTimestamp()
        );
    }
}
