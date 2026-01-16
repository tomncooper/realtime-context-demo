package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Result record for fleet utilization statistics.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FleetUtilizationResult(
    @JsonProperty("total_vehicles") int totalVehicles,
    @JsonProperty("status_breakdown") Map<String, Long> statusBreakdown,
    @JsonProperty("active_vehicles") long activeVehicles,
    @JsonProperty("idle_vehicles") long idleVehicles,
    @JsonProperty("maintenance_vehicles") long maintenanceVehicles,
    @JsonProperty("utilization_rate") String utilizationRate,
    @JsonProperty("average_load_kg") String averageLoadKg,
    @JsonProperty("average_fuel_level") String averageFuelLevel,
    @JsonProperty("message") String message
) {
    public static FleetUtilizationResult empty() {
        return new FleetUtilizationResult(
            0, Map.of(), 0, 0, 0, "0.0%", "0.0", "0.0%",
            "No vehicle data available for utilization analysis."
        );
    }

    public static FleetUtilizationResult of(
            int totalVehicles,
            Map<String, Long> statusBreakdown,
            long activeVehicles,
            long idleVehicles,
            long maintenanceVehicles,
            double utilizationRate,
            double averageLoadKg,
            double averageFuelLevel) {
        return new FleetUtilizationResult(
            totalVehicles,
            statusBreakdown,
            activeVehicles,
            idleVehicles,
            maintenanceVehicles,
            String.format("%.1f%%", utilizationRate),
            String.format("%.1f", averageLoadKg),
            String.format("%.1f%%", averageFuelLevel),
            null
        );
    }
}
