package com.smartship.api.model.reference;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for vehicle reference data from PostgreSQL.
 * Named VehicleRefDto to distinguish from Kafka Streams VehicleState.
 */
public record VehicleRefDto(
    @JsonProperty("vehicle_id") String vehicleId,
    @JsonProperty("vehicle_type") String vehicleType,
    @JsonProperty("license_plate") String licensePlate,
    @JsonProperty("capacity_kg") Double capacityKg,
    @JsonProperty("capacity_cubic_m") Double capacityCubicM,
    @JsonProperty("home_warehouse_id") String homeWarehouseId,
    @JsonProperty("status") String status,
    @JsonProperty("fuel_type") String fuelType
) {}
