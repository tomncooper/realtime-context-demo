package com.smartship.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for late shipment details from Kafka Streams state store.
 */
public record LateShipmentResponse(
    @JsonProperty("shipment_id") String shipmentId,
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("warehouse_id") String warehouseId,
    @JsonProperty("expected_delivery") long expectedDelivery,
    @JsonProperty("current_status") String currentStatus,
    @JsonProperty("destination_city") String destinationCity,
    @JsonProperty("destination_country") String destinationCountry,
    @JsonProperty("delay_minutes") long delayMinutes,
    @JsonProperty("last_updated") long lastUpdated
) {}
