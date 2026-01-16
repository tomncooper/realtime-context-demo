package com.smartship.api.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartship.api.model.hybrid.EnrichedCustomerOverview;
import com.smartship.api.model.hybrid.HybridQueryResult;
import com.smartship.api.model.tools.CustomerSearchResult;
import com.smartship.api.services.ToolOperationsService;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * LangChain4j tools for querying customer data.
 *
 * <p>These tools combine PostgreSQL reference data with real-time Kafka Streams
 * data to provide comprehensive customer information.</p>
 */
@ApplicationScoped
public class CustomerTools {

    private static final Logger LOG = Logger.getLogger(CustomerTools.class);

    @Inject
    ToolOperationsService operations;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Get comprehensive customer overview with real-time shipment and order statistics.
     * This combines PostgreSQL customer data with Kafka Streams real-time stats.
     */
    @Tool("Get comprehensive customer overview including real-time shipment and order statistics. Customer ID format is CUST-XXXX (e.g., CUST-0001). Returns customer details, SLA tier, shipment counts, order counts, and SLA compliance rate.")
    public String getCustomerOverview(String customerId) {
        LOG.infof("Tool called: getCustomerOverview for customer: %s", customerId);

        if (customerId == null || customerId.isBlank()) {
            return "{\"error\": \"Customer ID is required. Format: CUST-XXXX (e.g., CUST-0001)\"}";
        }

        try {
            HybridQueryResult<EnrichedCustomerOverview> result = operations.getCustomerOverview(customerId);

            if (result.result() == null) {
                return toJson(Map.of(
                    "message", "Customer not found: " + customerId,
                    "summary", result.summary(),
                    "query_time_ms", result.queryTimeMs()
                ));
            }

            EnrichedCustomerOverview overview = result.result();
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("customer_id", overview.customerId());
            response.put("company_name", overview.companyName());
            response.put("contact_email", overview.contactEmail() != null ? overview.contactEmail() : "N/A");
            response.put("sla_tier", overview.slaTier());
            response.put("account_status", overview.accountStatus());
            response.put("shipment_stats", Map.of(
                "total_shipments", overview.totalShipments(),
                "in_transit", overview.inTransitShipments(),
                "delivered", overview.deliveredShipments(),
                "exceptions", overview.exceptionShipments(),
                "on_time_rate", String.format("%.1f%%", overview.shipmentOnTimeRate())
            ));
            response.put("order_stats", Map.of(
                "total_orders", overview.totalOrders(),
                "pending", overview.pendingOrders(),
                "shipped", overview.shippedOrders(),
                "delivered", overview.deliveredOrders(),
                "at_risk", overview.atRiskOrders()
            ));
            response.put("sla_compliance_rate", String.format("%.1f%%", overview.slaComplianceRate()));
            response.put("summary", result.summary());
            response.put("query_time_ms", result.queryTimeMs());

            if (!result.warnings().isEmpty()) {
                response.put("warnings", result.warnings());
            }

            return toJson(response);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting customer overview for: %s", customerId);
            return "{\"error\": \"Failed to retrieve customer overview for " + customerId + ": " + e.getMessage() + "\"}";
        }
    }

    /**
     * Find customers by company name (partial match supported).
     * Useful for finding a customer when you only know part of the company name.
     */
    @Tool("Find customers by company name using partial match. For example, searching for 'Tech' will find 'TechCorp Ltd', 'Nordic Tech', etc. Returns a list of matching customers with their IDs and SLA tiers.")
    public String findCustomersByCompanyName(String companyName) {
        LOG.infof("Tool called: findCustomersByCompanyName for: %s", companyName);

        if (companyName == null || companyName.isBlank()) {
            return "{\"error\": \"Company name search term is required\"}";
        }

        try {
            CustomerSearchResult result = operations.findCustomersByCompanyName(companyName);
            return toJson(result);
        } catch (Exception e) {
            LOG.errorf(e, "Error finding customers by company name: %s", companyName);
            return "{\"error\": \"Failed to search customers: " + e.getMessage() + "\"}";
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
