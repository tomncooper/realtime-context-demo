package com.smartship.api.mcp;

import com.smartship.api.model.tools.FleetStatusResult;
import com.smartship.api.model.tools.FleetUtilizationResult;
import com.smartship.api.model.tools.ToolError;
import com.smartship.api.model.tools.VehicleStateResult;
import com.smartship.api.services.ToolOperationsService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * MCP tools for vehicle and fleet-related queries.
 * These tools are exposed via the MCP protocol for external AI agents.
 */
@Singleton
public class VehicleMcpTools {

    private static final Logger LOG = Logger.getLogger(VehicleMcpTools.class);

    @Inject
    ToolOperationsService operations;

    @Tool(name = "vehicle_state", description = "Get real-time telemetry for a specific vehicle including GPS location (latitude, longitude), speed, heading, fuel level, current load, and operational status. Vehicle ID format: VEH-XXX (e.g., VEH-001 through VEH-050).")
    public Object getVehicleState(
            @ToolArg(description = "Vehicle ID in format VEH-XXX (e.g., VEH-001, VEH-025)")
            String vehicleId) {
        LOG.infof("MCP Tool: getVehicleState (vehicleId=%s)", vehicleId);
        if (vehicleId == null || vehicleId.isBlank()) {
            return ToolError.validation("Vehicle ID is required. Format: VEH-XXX");
        }
        try {
            return operations.getVehicleState(vehicleId);
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getVehicleState");
            return ToolError.of("Failed to retrieve vehicle state for: " + vehicleId, e);
        }
    }

    @Tool(name = "all_vehicle_states", description = "Get current status for all vehicles in the fleet. Returns a fleet-wide overview with vehicle count, status summary (EN_ROUTE, IDLE, LOADING, UNLOADING, MAINTENANCE), and details for each vehicle.")
    public Object getAllVehicleStates() {
        LOG.info("MCP Tool: getAllVehicleStates");
        try {
            return operations.getAllVehicleStates();
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getAllVehicleStates");
            return ToolError.of("Failed to retrieve vehicle states", e);
        }
    }

    @Tool(name = "fleet_utilization", description = "Get fleet-wide utilization statistics including active/idle/maintenance vehicle counts, utilization rate percentage, average load in kg, and average fuel level. Useful for understanding overall fleet efficiency.")
    public Object getFleetUtilization() {
        LOG.info("MCP Tool: getFleetUtilization");
        try {
            return operations.getFleetUtilization();
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getFleetUtilization");
            return ToolError.of("Failed to calculate fleet utilization", e);
        }
    }
}
