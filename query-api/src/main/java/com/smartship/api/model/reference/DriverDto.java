package com.smartship.api.model.reference;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * DTO for driver reference data from PostgreSQL.
 */
public record DriverDto(
    @JsonProperty("driver_id") String driverId,
    @JsonProperty("first_name") String firstName,
    @JsonProperty("last_name") String lastName,
    @JsonProperty("license_type") String licenseType,
    @JsonProperty("certifications") List<String> certifications,
    @JsonProperty("assigned_vehicle_id") String assignedVehicleId,
    @JsonProperty("home_warehouse_id") String homeWarehouseId,
    @JsonProperty("status") String status
) {}
