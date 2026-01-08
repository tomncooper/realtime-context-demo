package com.smartship.api.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartship.api.model.hybrid.EnrichedCustomerOverview;
import com.smartship.api.model.hybrid.HybridQueryResult;
import com.smartship.api.model.reference.CustomerDto;
import com.smartship.api.services.PostgresQueryService;
import com.smartship.api.services.QueryOrchestrationService;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LangChain4j tools for querying customer data.
 *
 * <p>These tools combine PostgreSQL reference data with real-time Kafka Streams
 * data to provide comprehensive customer information.</p>
 */
@ApplicationScoped
public class CustomerTools {

    private static final Logger LOG = Logger.getLogger(CustomerTools.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Inject
    QueryOrchestrationService orchestrationService;

    @Inject
    PostgresQueryService postgresQuery;

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

        // Normalize customer ID format
        String normalizedId = normalizeCustomerId(customerId);

        try {
            HybridQueryResult<EnrichedCustomerOverview> result = orchestrationService
                .getCustomerOverview(normalizedId)
                .await().atMost(TIMEOUT);

            if (result.result() == null) {
                return toJson(Map.of(
                    "message", "Customer not found: " + normalizedId,
                    "summary", result.summary(),
                    "query_time_ms", result.queryTimeMs()
                ));
            }

            EnrichedCustomerOverview overview = result.result();
            Map<String, Object> response = Map.ofEntries(
                Map.entry("customer_id", overview.customerId()),
                Map.entry("company_name", overview.companyName()),
                Map.entry("contact_email", overview.contactEmail() != null ? overview.contactEmail() : "N/A"),
                Map.entry("sla_tier", overview.slaTier()),
                Map.entry("account_status", overview.accountStatus()),
                Map.entry("shipment_stats", Map.of(
                    "total_shipments", overview.totalShipments(),
                    "in_transit", overview.inTransitShipments(),
                    "delivered", overview.deliveredShipments(),
                    "exceptions", overview.exceptionShipments(),
                    "on_time_rate", String.format("%.1f%%", overview.shipmentOnTimeRate())
                )),
                Map.entry("order_stats", Map.of(
                    "total_orders", overview.totalOrders(),
                    "pending", overview.pendingOrders(),
                    "shipped", overview.shippedOrders(),
                    "delivered", overview.deliveredOrders(),
                    "at_risk", overview.atRiskOrders()
                )),
                Map.entry("sla_compliance_rate", String.format("%.1f%%", overview.slaComplianceRate())),
                Map.entry("summary", result.summary()),
                Map.entry("query_time_ms", result.queryTimeMs())
            );

            // Add warnings if any
            if (!result.warnings().isEmpty()) {
                response = new java.util.HashMap<>(response);
                response.put("warnings", result.warnings());
            }

            return toJson(response);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting customer overview for: %s", normalizedId);
            return "{\"error\": \"Failed to retrieve customer overview for " + normalizedId + ": " + e.getMessage() + "\"}";
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
            List<CustomerDto> customers = postgresQuery
                .findCustomersByCompanyName(companyName)
                .await().atMost(TIMEOUT);

            if (customers == null || customers.isEmpty()) {
                return toJson(Map.of(
                    "message", "No customers found matching: " + companyName,
                    "count", 0,
                    "search_term", companyName
                ));
            }

            // Limit to first 10 results for readability
            List<Map<String, Object>> customerList = customers.stream()
                .limit(10)
                .map(c -> Map.<String, Object>of(
                    "customer_id", c.customerId(),
                    "company_name", c.companyName(),
                    "sla_tier", c.slaTier(),
                    "account_status", c.accountStatus(),
                    "contact_email", c.contactEmail() != null ? c.contactEmail() : "N/A"
                ))
                .collect(Collectors.toList());

            return toJson(Map.of(
                "count", customers.size(),
                "showing", customerList.size(),
                "search_term", companyName,
                "customers", customerList,
                "note", customers.size() > 10 ? "Showing first 10 of " + customers.size() + " results" : null
            ));
        } catch (Exception e) {
            LOG.errorf(e, "Error finding customers by company name: %s", companyName);
            return "{\"error\": \"Failed to search customers: " + e.getMessage() + "\"}";
        }
    }

    private String normalizeCustomerId(String customerId) {
        String normalized = customerId.toUpperCase().trim();
        if (!normalized.startsWith("CUST-")) {
            // Try to parse as a number and format properly
            try {
                int id = Integer.parseInt(normalized.replaceAll("[^0-9]", ""));
                normalized = String.format("CUST-%04d", id);
            } catch (NumberFormatException e) {
                normalized = "CUST-" + normalized;
            }
        }
        return normalized;
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
