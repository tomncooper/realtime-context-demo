package com.smartship.api.mcp;

import com.smartship.api.model.tools.CustomerShipmentStatsResult;
import com.smartship.api.model.tools.LateShipmentsResult;
import com.smartship.api.model.tools.ShipmentByStatusResult;
import com.smartship.api.model.tools.ShipmentStatusResult;
import com.smartship.api.model.tools.ToolError;
import com.smartship.api.services.ToolOperationsService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * MCP tools for shipment-related queries.
 * These tools are exposed via the MCP protocol for external AI agents.
 */
@Singleton
public class ShipmentMcpTools {

    private static final Logger LOG = Logger.getLogger(ShipmentMcpTools.class);

    @Inject
    ToolOperationsService operations;

    @Tool(name = "shipment_status_counts", description = "Get the count of shipments for each status (CREATED, PICKED, PACKED, DISPATCHED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, CANCELLED). Returns status counts and total shipments in the SmartShip logistics system.")
    public Object getShipmentStatusCounts() {
        LOG.info("MCP Tool: getShipmentStatusCounts");
        try {
            return operations.getShipmentStatusCounts();
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getShipmentStatusCounts");
            return ToolError.of("Failed to retrieve shipment status counts", e);
        }
    }

    @Tool(name = "late_shipments", description = "Get shipments that are currently late (delayed past expected delivery time). Returns total count, summary, and details of the most delayed shipments including shipment IDs, customer IDs, and delay duration.")
    public Object getLateShipments() {
        LOG.info("MCP Tool: getLateShipments");
        try {
            return operations.getLateShipments();
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getLateShipments");
            return ToolError.of("Failed to retrieve late shipments", e);
        }
    }

    @Tool(name = "shipment_by_status", description = "Get the count of shipments for a specific status. Valid statuses: CREATED, PICKED, PACKED, DISPATCHED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, CANCELLED.")
    public Object getShipmentByStatus(
            @ToolArg(description = "Shipment status to query (e.g., IN_TRANSIT, DELIVERED, EXCEPTION)")
            String status) {
        LOG.infof("MCP Tool: getShipmentByStatus (status=%s)", status);
        if (status == null || status.isBlank()) {
            return ToolError.validation("Status parameter is required");
        }
        try {
            return operations.getShipmentByStatus(status);
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getShipmentByStatus");
            return ToolError.of("Failed to retrieve shipment count for status: " + status, e);
        }
    }

    @Tool(name = "customer_shipment_stats", description = "Get shipment statistics for a specific customer including total shipments, in-transit count, delivered count, and exception count. Customer ID format: CUST-XXXX (e.g., CUST-0001 through CUST-0200).")
    public Object getCustomerShipmentStats(
            @ToolArg(description = "Customer ID in format CUST-XXXX (e.g., CUST-0001, CUST-0050)")
            String customerId) {
        LOG.infof("MCP Tool: getCustomerShipmentStats (customerId=%s)", customerId);
        if (customerId == null || customerId.isBlank()) {
            return ToolError.validation("Customer ID is required. Format: CUST-XXXX");
        }
        try {
            return operations.getCustomerShipmentStats(customerId);
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getCustomerShipmentStats");
            return ToolError.of("Failed to retrieve shipment stats for customer: " + customerId, e);
        }
    }
}
