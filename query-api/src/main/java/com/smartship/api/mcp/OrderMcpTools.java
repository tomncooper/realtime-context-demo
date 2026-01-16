package com.smartship.api.mcp;

import com.smartship.api.model.tools.CustomerOrderStatsResult;
import com.smartship.api.model.tools.OrderStateResult;
import com.smartship.api.model.tools.OrdersAtRiskResult;
import com.smartship.api.model.tools.ToolError;
import com.smartship.api.services.ToolOperationsService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * MCP tools for order-related queries.
 * These tools are exposed via the MCP protocol for external AI agents.
 */
@Singleton
public class OrderMcpTools {

    private static final Logger LOG = Logger.getLogger(OrderMcpTools.class);

    @Inject
    ToolOperationsService operations;

    @Tool(name = "orders_at_sla_risk", description = "Get orders that are at risk of missing their SLA deadline. Returns total count, summary, and details of at-risk orders including order IDs, customer IDs, priority, and time remaining before SLA breach.")
    public Object getOrdersAtSlaRisk() {
        LOG.info("MCP Tool: getOrdersAtSlaRisk");
        try {
            return operations.getOrdersAtSlaRisk();
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getOrdersAtSlaRisk");
            return ToolError.of("Failed to retrieve orders at SLA risk", e);
        }
    }

    @Tool(name = "order_state", description = "Get current state of a specific order including customer ID, status, priority, associated shipment IDs, and timestamps. Order ID format: ORD-XXXXX (e.g., ORD-00001).")
    public Object getOrderState(
            @ToolArg(description = "Order ID in format ORD-XXXXX (e.g., ORD-00001, ORD-12345)")
            String orderId) {
        LOG.infof("MCP Tool: getOrderState (orderId=%s)", orderId);
        if (orderId == null || orderId.isBlank()) {
            return ToolError.validation("Order ID is required. Format: ORD-XXXXX");
        }
        try {
            return operations.getOrderState(orderId);
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getOrderState");
            return ToolError.of("Failed to retrieve order state for: " + orderId, e);
        }
    }

    @Tool(name = "customer_order_stats", description = "Get order statistics for a specific customer including total orders, pending, confirmed, shipped, delivered, and at-risk counts. Customer ID format: CUST-XXXX (e.g., CUST-0001).")
    public Object getCustomerOrderStats(
            @ToolArg(description = "Customer ID in format CUST-XXXX (e.g., CUST-0001)")
            String customerId) {
        LOG.infof("MCP Tool: getCustomerOrderStats (customerId=%s)", customerId);
        if (customerId == null || customerId.isBlank()) {
            return ToolError.validation("Customer ID is required. Format: CUST-XXXX");
        }
        try {
            return operations.getCustomerOrderStats(customerId);
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getCustomerOrderStats");
            return ToolError.of("Failed to retrieve order stats for customer: " + customerId, e);
        }
    }
}
