package com.smartship.api.mcp;

import com.smartship.api.model.hybrid.EnrichedCustomerOverview;
import com.smartship.api.model.hybrid.HybridQueryResult;
import com.smartship.api.model.tools.CustomerSearchResult;
import com.smartship.api.model.tools.ToolError;
import com.smartship.api.services.ToolOperationsService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * MCP tools for customer-related queries.
 * These tools are exposed via the MCP protocol for external AI agents.
 */
@Singleton
public class CustomerMcpTools {

    private static final Logger LOG = Logger.getLogger(CustomerMcpTools.class);

    @Inject
    ToolOperationsService operations;

    @Tool(name = "customer_overview", description = "Get comprehensive customer overview including company details, SLA tier, account status, real-time shipment statistics (total, in-transit, delivered, exceptions), order statistics (total, pending, shipped, delivered, at-risk), and SLA compliance rate. Customer ID format: CUST-XXXX (e.g., CUST-0001).")
    public Object getCustomerOverview(
            @ToolArg(description = "Customer ID in format CUST-XXXX (e.g., CUST-0001, CUST-0050)")
            String customerId) {
        LOG.infof("MCP Tool: getCustomerOverview (customerId=%s)", customerId);
        if (customerId == null || customerId.isBlank()) {
            return ToolError.validation("Customer ID is required. Format: CUST-XXXX");
        }
        try {
            HybridQueryResult<EnrichedCustomerOverview> result = operations.getCustomerOverview(customerId);
            return result;
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getCustomerOverview");
            return ToolError.of("Failed to retrieve customer overview for: " + customerId, e);
        }
    }

    @Tool(name = "customer_sla_compliance", description = "Get detailed SLA compliance metrics for a customer including delivery performance, at-risk orders, and compliance rate percentage. Customer ID format: CUST-XXXX.")
    public Object getCustomerSlaCompliance(
            @ToolArg(description = "Customer ID in format CUST-XXXX (e.g., CUST-0001)")
            String customerId) {
        LOG.infof("MCP Tool: getCustomerSlaCompliance (customerId=%s)", customerId);
        if (customerId == null || customerId.isBlank()) {
            return ToolError.validation("Customer ID is required. Format: CUST-XXXX");
        }
        try {
            HybridQueryResult<Map<String, Object>> result = operations.getCustomerSlaCompliance(customerId);
            return result;
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: getCustomerSlaCompliance");
            return ToolError.of("Failed to retrieve SLA compliance for: " + customerId, e);
        }
    }

    @Tool(name = "find_customers_by_name", description = "Find customers by company name using partial match. For example, searching for 'Tech' will find 'TechCorp Ltd', 'Nordic Tech', etc. Returns matching customers with their IDs, company names, SLA tiers, and account status.")
    public Object findCustomersByName(
            @ToolArg(description = "Company name search term (partial match supported)")
            String companyName) {
        LOG.infof("MCP Tool: findCustomersByName (name=%s)", companyName);
        if (companyName == null || companyName.isBlank()) {
            return ToolError.validation("Company name search term is required");
        }
        try {
            return operations.findCustomersByCompanyName(companyName);
        } catch (Exception e) {
            LOG.errorf(e, "MCP Tool error: findCustomersByName");
            return ToolError.of("Failed to search customers by name: " + companyName, e);
        }
    }
}
