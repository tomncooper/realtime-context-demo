package com.smartship.api.model.reference;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for route reference data from PostgreSQL.
 */
public record RouteDto(
    @JsonProperty("route_id") String routeId,
    @JsonProperty("origin_warehouse_id") String originWarehouseId,
    @JsonProperty("destination_city") String destinationCity,
    @JsonProperty("destination_country") String destinationCountry,
    @JsonProperty("distance_km") Double distanceKm,
    @JsonProperty("estimated_hours") Double estimatedHours,
    @JsonProperty("route_type") String routeType,
    @JsonProperty("active") Boolean active
) {}
