package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartship.api.model.reference.CustomerDto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result record for customer search operations.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomerSearchResult(
    @JsonProperty("count") int count,
    @JsonProperty("showing") int showing,
    @JsonProperty("search_term") String searchTerm,
    @JsonProperty("customers") List<Map<String, Object>> customers,
    @JsonProperty("note") String note,
    @JsonProperty("message") String message
) {
    private static final int DEFAULT_LIMIT = 10;

    public static CustomerSearchResult empty(String searchTerm) {
        return new CustomerSearchResult(
            0, 0, searchTerm, List.of(), null,
            "No customers found matching: " + searchTerm
        );
    }

    public static CustomerSearchResult of(String searchTerm, List<CustomerDto> allCustomers) {
        return of(searchTerm, allCustomers, DEFAULT_LIMIT);
    }

    public static CustomerSearchResult of(String searchTerm, List<CustomerDto> allCustomers, int limit) {
        int total = allCustomers.size();
        List<Map<String, Object>> limited = allCustomers.stream()
            .limit(limit)
            .map(c -> Map.<String, Object>of(
                "customer_id", c.customerId(),
                "company_name", c.companyName(),
                "sla_tier", c.slaTier(),
                "account_status", c.accountStatus(),
                "contact_email", c.contactEmail() != null ? c.contactEmail() : "N/A"
            ))
            .collect(Collectors.toList());

        String note = total > limit
            ? String.format("Showing first %d of %d results", limit, total)
            : null;

        return new CustomerSearchResult(total, limited.size(), searchTerm, limited, note, null);
    }
}
