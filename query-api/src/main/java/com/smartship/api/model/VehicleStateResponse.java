package com.smartship.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response DTO for vehicle current state from Kafka Streams state store.
 */
public record VehicleStateResponse(
    @JsonProperty("vehicle_id") String vehicleId,
    @JsonProperty("driver_id") String driverId,
    double latitude,
    double longitude,
    @JsonProperty("speed_kmh") double speedKmh,
    @JsonProperty("fuel_level_percent") double fuelLevelPercent,
    String status,
    @JsonProperty("current_load_kg") double currentLoadKg,
    @JsonProperty("current_load_items") int currentLoadItems,
    @JsonProperty("shipment_ids") List<String> shipmentIds,
    @JsonProperty("odometer_km") double odometerKm,
    @JsonProperty("last_updated") long lastUpdated
) {}
