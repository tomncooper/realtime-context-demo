package com.smartship.api.model.reference;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for warehouse reference data from PostgreSQL.
 */
public record WarehouseDto(
    @JsonProperty("warehouse_id") String warehouseId,
    @JsonProperty("name") String name,
    @JsonProperty("city") String city,
    @JsonProperty("country") String country,
    @JsonProperty("latitude") Double latitude,
    @JsonProperty("longitude") Double longitude,
    @JsonProperty("status") String status
) {}
