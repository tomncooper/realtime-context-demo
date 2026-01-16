package com.smartship.api.mcp;

import com.smartship.api.model.tools.AvailableDriversResult;
import com.smartship.api.model.tools.RoutesByOriginResult;
import com.smartship.api.model.tools.ToolError;
import com.smartship.api.services.ToolOperationsService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * MCP tools for reference data queries.
 * These tools are exposed via the MCP protocol for external AI agents.
 */
@Singleton
public class ReferenceMcpTools {

    private static final Logger LOG = Logger.getLogger(ReferenceMcpTools.class);

    @Inject
    ToolOperationsService operations;

    @Tool(name = "available_drivers", description = "Get list of currently available drivers (status=AVAILABLE). Returns driver IDs (DRV-XXX), names, license types, home warehouse assignments, and status.")
    public Object getAvailableDrivers() {
        LOG.info("MCP Tool: getAvailableDrivers");
        try {
            return operations.getAvailableDrivers();
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getAvailableDrivers");
            return ToolError.of("Failed to retrieve available drivers", e);
        }
    }

    @Tool(name = "routes_by_origin", description = "Get delivery routes originating from a specific warehouse. Returns route IDs, destination cities and countries, distance in km, estimated travel hours, route type, and whether the route is active. Warehouse ID format: WH-XXX (e.g., WH-RTM, WH-FRA).")
    public Object getRoutesByOrigin(
            @ToolArg(description = "Origin warehouse ID (e.g., WH-RTM, WH-FRA, WH-BCN)")
            String warehouseId) {
        LOG.infof("MCP Tool: getRoutesByOrigin (warehouseId=%s)", warehouseId);
        if (warehouseId == null || warehouseId.isBlank()) {
            return ToolError.validation("Warehouse ID is required. Format: WH-XXX");
        }
        try {
            return operations.getRoutesByOrigin(warehouseId);
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getRoutesByOrigin");
            return ToolError.of("Failed to retrieve routes from warehouse: " + warehouseId, e);
        }
    }
}
