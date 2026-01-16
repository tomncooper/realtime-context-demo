package com.smartship.api.mcp;

import com.smartship.api.model.hybrid.HybridQueryResult;
import com.smartship.api.model.tools.ToolError;
import com.smartship.api.model.tools.WarehouseListResult;
import com.smartship.api.model.tools.WarehousePerformanceResult;
import com.smartship.api.services.ToolOperationsService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * MCP tools for warehouse-related queries.
 * These tools are exposed via the MCP protocol for external AI agents.
 */
@Singleton
public class WarehouseMcpTools {

    private static final Logger LOG = Logger.getLogger(WarehouseMcpTools.class);

    @Inject
    ToolOperationsService operations;

    @Tool(name = "warehouse_list", description = "Get list of all warehouses in the SmartShip logistics network. Returns warehouse IDs (WH-RTM for Rotterdam, WH-FRA for Frankfurt, WH-BCN for Barcelona, WH-WAW for Warsaw, WH-STO for Stockholm), names, cities, countries, and operational status.")
    public Object getWarehouseList() {
        LOG.info("MCP Tool: getWarehouseList");
        try {
            return operations.getWarehouseList();
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getWarehouseList");
            return ToolError.of("Failed to retrieve warehouse list", e);
        }
    }

    @Tool(name = "warehouse_status", description = "Get detailed operational status for a specific warehouse including location details, assigned vehicle count, driver count, and real-time operational metrics. Warehouse ID format: WH-XXX (e.g., WH-RTM, WH-FRA).")
    public Object getWarehouseStatus(
            @ToolArg(description = "Warehouse ID (e.g., WH-RTM for Rotterdam, WH-FRA for Frankfurt, WH-BCN for Barcelona, WH-WAW for Warsaw, WH-STO for Stockholm)")
            String warehouseId) {
        LOG.infof("MCP Tool: getWarehouseStatus (warehouseId=%s)", warehouseId);
        if (warehouseId == null || warehouseId.isBlank()) {
            return ToolError.validation("Warehouse ID is required. Format: WH-XXX");
        }
        try {
            HybridQueryResult<Map<String, Object>> result = operations.getWarehouseStatus(warehouseId);
            return result;
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getWarehouseStatus");
            return ToolError.of("Failed to retrieve warehouse status for: " + warehouseId, e);
        }
    }

    @Tool(name = "warehouse_performance", description = "Get hourly delivery performance metrics for a warehouse including delivery counts, on-time rates, and throughput. Data is aggregated in 1-hour windows. Warehouse ID format: WH-XXX.")
    public Object getWarehousePerformance(
            @ToolArg(description = "Warehouse ID (e.g., WH-RTM, WH-FRA)")
            String warehouseId) {
        LOG.infof("MCP Tool: getWarehousePerformance (warehouseId=%s)", warehouseId);
        if (warehouseId == null || warehouseId.isBlank()) {
            return ToolError.validation("Warehouse ID is required. Format: WH-XXX");
        }
        try {
            return operations.getWarehousePerformance(warehouseId);
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getWarehousePerformance");
            return ToolError.of("Failed to retrieve warehouse performance for: " + warehouseId, e);
        }
    }
}
