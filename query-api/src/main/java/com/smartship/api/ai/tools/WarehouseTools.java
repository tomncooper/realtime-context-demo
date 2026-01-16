package com.smartship.api.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartship.api.model.hybrid.HybridQueryResult;
import com.smartship.api.model.tools.WarehouseListResult;
import com.smartship.api.services.ToolOperationsService;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * LangChain4j tools for querying warehouse data.
 *
 * <p>These tools provide information about warehouses including locations,
 * real-time metrics, and operational status.</p>
 */
@ApplicationScoped
public class WarehouseTools {

    private static final Logger LOG = Logger.getLogger(WarehouseTools.class);

    @Inject
    ToolOperationsService operations;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Get a list of all warehouses with their locations and status.
     * Returns warehouse ID, name, city, country, and operational status.
     */
    @Tool("Get a list of all warehouses in the SmartShip network. Returns warehouse IDs (e.g., WH-RTM, WH-FRA), names, cities, countries, and operational status.")
    public String getWarehouseList() {
        LOG.info("Tool called: getWarehouseList");
        try {
            WarehouseListResult result = operations.getWarehouseList();
            return toJson(result);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting warehouse list");
            return "{\"error\": \"Failed to retrieve warehouse list: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get detailed operational status for a specific warehouse including real-time metrics.
     * Warehouse ID format is WH-XXX (e.g., WH-RTM for Rotterdam, WH-FRA for Frankfurt).
     */
    @Tool("Get detailed operational status for a specific warehouse including location, vehicle count, driver count, and real-time metrics. Warehouse ID format is WH-XXX (e.g., WH-RTM for Rotterdam, WH-FRA for Frankfurt, WH-BCN for Barcelona, WH-WAW for Warsaw, WH-STO for Stockholm).")
    public String getWarehouseStatus(String warehouseId) {
        LOG.infof("Tool called: getWarehouseStatus for warehouse: %s", warehouseId);

        if (warehouseId == null || warehouseId.isBlank()) {
            return "{\"error\": \"Warehouse ID is required. Format: WH-XXX (e.g., WH-RTM, WH-FRA)\"}";
        }

        try {
            HybridQueryResult<Map<String, Object>> result = operations.getWarehouseStatus(warehouseId);

            if (result.result() == null) {
                return toJson(Map.of(
                    "message", "Warehouse not found: " + warehouseId,
                    "summary", result.summary(),
                    "query_time_ms", result.queryTimeMs()
                ));
            }

            Map<String, Object> status = new java.util.LinkedHashMap<>(result.result());
            status.put("summary", result.summary());
            status.put("query_time_ms", result.queryTimeMs());

            return toJson(status);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting warehouse status for: %s", warehouseId);
            return "{\"error\": \"Failed to retrieve warehouse status for " + warehouseId + ": " + e.getMessage() + "\"}";
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
