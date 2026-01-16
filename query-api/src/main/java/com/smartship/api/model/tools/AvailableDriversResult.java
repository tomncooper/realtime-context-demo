package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartship.api.model.reference.DriverDto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result record for available drivers query.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AvailableDriversResult(
    @JsonProperty("count") int count,
    @JsonProperty("drivers") List<Map<String, Object>> drivers,
    @JsonProperty("message") String message
) {
    public static AvailableDriversResult empty() {
        return new AvailableDriversResult(
            0, List.of(),
            "No drivers currently available."
        );
    }

    public static AvailableDriversResult of(List<DriverDto> driverList) {
        List<Map<String, Object>> mapped = driverList.stream()
            .map(d -> Map.<String, Object>of(
                "driver_id", d.driverId(),
                "first_name", d.firstName(),
                "last_name", d.lastName(),
                "license_type", d.licenseType(),
                "home_warehouse_id", d.homeWarehouseId(),
                "status", d.status()
            ))
            .collect(Collectors.toList());

        return new AvailableDriversResult(mapped.size(), mapped, null);
    }
}
