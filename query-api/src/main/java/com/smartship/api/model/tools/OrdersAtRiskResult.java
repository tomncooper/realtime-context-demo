package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Result record for orders at SLA risk.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrdersAtRiskResult(
    @JsonProperty("total_at_risk") int totalAtRisk,
    @JsonProperty("showing") int showing,
    @JsonProperty("summary") String summary,
    @JsonProperty("at_risk_orders") List<Map<String, Object>> atRiskOrders,
    @JsonProperty("note") String note,
    @JsonProperty("message") String message
) {
    public static OrdersAtRiskResult empty() {
        return new OrdersAtRiskResult(
            0, 0, null, List.of(), null,
            "No orders currently at SLA risk. All orders are on track."
        );
    }

    public static OrdersAtRiskResult of(List<Map<String, Object>> allAtRisk, int limit) {
        int total = allAtRisk.size();
        List<Map<String, Object>> limited = allAtRisk.stream().limit(limit).toList();
        String note = total > limit
            ? String.format("Showing top %d of %d at-risk orders.", limit, total)
            : null;
        return new OrdersAtRiskResult(
            total,
            limited.size(),
            String.format("There are %d orders at risk of missing SLA.", total),
            limited,
            note,
            null
        );
    }
}
