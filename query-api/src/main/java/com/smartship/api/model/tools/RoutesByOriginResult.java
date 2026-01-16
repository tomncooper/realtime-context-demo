package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartship.api.model.reference.RouteDto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result record for routes by origin warehouse query.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoutesByOriginResult(
    @JsonProperty("origin_warehouse_id") String originWarehouseId,
    @JsonProperty("count") int count,
    @JsonProperty("routes") List<Map<String, Object>> routes,
    @JsonProperty("message") String message
) {
    public static RoutesByOriginResult empty(String warehouseId) {
        return new RoutesByOriginResult(
            warehouseId, 0, List.of(),
            "No routes found from warehouse " + warehouseId
        );
    }

    public static RoutesByOriginResult of(String warehouseId, List<RouteDto> routeList) {
        List<Map<String, Object>> mapped = routeList.stream()
            .map(r -> Map.<String, Object>of(
                "route_id", r.routeId(),
                "destination_city", r.destinationCity(),
                "destination_country", r.destinationCountry(),
                "distance_km", r.distanceKm(),
                "estimated_hours", r.estimatedHours(),
                "route_type", r.routeType(),
                "active", r.active()
            ))
            .collect(Collectors.toList());

        return new RoutesByOriginResult(warehouseId, mapped.size(), mapped, null);
    }
}
