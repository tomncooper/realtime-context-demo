package com.smartship.api.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartship.api.model.reference.VehicleRefDto;
import com.smartship.api.model.tools.FleetStatusResult;
import com.smartship.api.model.tools.FleetUtilizationResult;
import com.smartship.api.model.tools.VehicleStateResult;
import com.smartship.api.services.PostgresQueryService;
import com.smartship.api.services.ToolOperationsService;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LangChain4j tools for querying vehicle data from Kafka Streams state stores
 * and PostgreSQL reference data.
 *
 * <p>These tools provide real-time vehicle status, fleet overview, and
 * utilization statistics.</p>
 */
@ApplicationScoped
public class VehicleTools {

    private static final Logger LOG = Logger.getLogger(VehicleTools.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Inject
    ToolOperationsService operations;

    @Inject
    PostgresQueryService postgresQuery;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Get real-time status for a specific vehicle including location, speed, fuel level, and current load.
     */
    @Tool("Get real-time status for a specific vehicle including location, speed, fuel level, and current load. Vehicle ID format is VEH-XXX (e.g., VEH-001 through VEH-050). Returns latitude, longitude, speed, heading, fuel level, load information, and current status.")
    public String getVehicleStatus(String vehicleId) {
        LOG.infof("Tool called: getVehicleStatus for vehicle: %s", vehicleId);

        if (vehicleId == null || vehicleId.isBlank()) {
            return "{\"error\": \"Vehicle ID is required. Format: VEH-XXX (e.g., VEH-001)\"}";
        }

        try {
            VehicleStateResult result = operations.getVehicleState(vehicleId);
            return toJson(result);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting vehicle status for: %s", vehicleId);
            return "{\"error\": \"Failed to retrieve vehicle status for " + vehicleId + ": " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get current status for all vehicles in the fleet.
     */
    @Tool("Get current status for all vehicles in the fleet. Returns a list of all vehicles with their real-time location, speed, fuel level, load, and status. Use this to get a fleet-wide overview.")
    public String getAllVehicleStates() {
        LOG.info("Tool called: getAllVehicleStates");

        try {
            FleetStatusResult result = operations.getAllVehicleStates();
            return toJson(result);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting all vehicle states");
            return "{\"error\": \"Failed to retrieve vehicle states: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get vehicles assigned to a specific warehouse.
     */
    @Tool("Get all vehicles assigned to a specific warehouse by their home warehouse ID. Warehouse ID format is WH-XXX (e.g., WH-RTM for Rotterdam, WH-FRA for Frankfurt, WH-BCN for Barcelona, WH-WAW for Warsaw, WH-STO for Stockholm). Returns vehicle details including type, capacity, and status.")
    public String getVehiclesByWarehouse(String warehouseId) {
        LOG.infof("Tool called: getVehiclesByWarehouse for: %s", warehouseId);

        if (warehouseId == null || warehouseId.isBlank()) {
            return "{\"error\": \"Warehouse ID is required. Format: WH-XXX (e.g., WH-RTM)\"}";
        }

        String normalizedId = operations.normalizeWarehouseId(warehouseId);

        try {
            List<VehicleRefDto> vehicles = postgresQuery
                .findVehiclesByHomeWarehouse(normalizedId)
                .await().atMost(TIMEOUT);

            if (vehicles == null || vehicles.isEmpty()) {
                return toJson(Map.of(
                    "message", "No vehicles found for warehouse " + normalizedId,
                    "warehouse_id", normalizedId,
                    "count", 0
                ));
            }

            List<Map<String, Object>> vehicleList = vehicles.stream()
                .map(v -> {
                    Map<String, Object> vehicleMap = new HashMap<>();
                    vehicleMap.put("vehicle_id", v.vehicleId());
                    vehicleMap.put("vehicle_type", v.vehicleType());
                    vehicleMap.put("license_plate", v.licensePlate());
                    vehicleMap.put("capacity_kg", v.capacityKg());
                    vehicleMap.put("capacity_m3", v.capacityCubicM());
                    vehicleMap.put("fuel_type", v.fuelType());
                    vehicleMap.put("status", v.status());
                    return vehicleMap;
                })
                .collect(Collectors.toList());

            return toJson(Map.of(
                "warehouse_id", normalizedId,
                "count", vehicles.size(),
                "vehicles", vehicleList
            ));

        } catch (Exception e) {
            LOG.errorf(e, "Error getting vehicles for warehouse: %s", normalizedId);
            return "{\"error\": \"Failed to retrieve vehicles for warehouse " + normalizedId + ": " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get fleet-wide utilization statistics.
     */
    @Tool("Get fleet-wide utilization statistics including the number of vehicles by status (EN_ROUTE, IDLE, LOADING, UNLOADING, MAINTENANCE), average load percentage, and overall fleet capacity usage. Useful for understanding fleet efficiency.")
    public String getFleetUtilization() {
        LOG.info("Tool called: getFleetUtilization");

        try {
            FleetUtilizationResult result = operations.getFleetUtilization();
            return toJson(result);
        } catch (Exception e) {
            LOG.errorf(e, "Error calculating fleet utilization");
            return "{\"error\": \"Failed to calculate fleet utilization: " + e.getMessage() + "\"}";
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize object to JSON", e);
            return "{\"error\": \"Failed to serialize response\"}";
        }
    }
}
