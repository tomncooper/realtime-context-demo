package com.smartship.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for customer shipment statistics from Kafka Streams state store.
 */
public record CustomerShipmentStatsResponse(
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("total_shipments") long totalShipments,
    @JsonProperty("in_transit_count") long inTransitCount,
    @JsonProperty("delivered_count") long deliveredCount,
    @JsonProperty("late_count") long lateCount,
    @JsonProperty("cancelled_count") long cancelledCount,
    @JsonProperty("exception_count") long exceptionCount,
    @JsonProperty("last_updated") long lastUpdated
) {}
