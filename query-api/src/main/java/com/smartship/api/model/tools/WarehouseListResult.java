package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartship.api.model.reference.WarehouseDto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result record for warehouse list query.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WarehouseListResult(
    @JsonProperty("count") int count,
    @JsonProperty("warehouses") List<Map<String, Object>> warehouses,
    @JsonProperty("message") String message
) {
    public static WarehouseListResult empty() {
        return new WarehouseListResult(
            0, List.of(),
            "No warehouses found in the system."
        );
    }

    public static WarehouseListResult of(List<WarehouseDto> warehouseList) {
        List<Map<String, Object>> mapped = warehouseList.stream()
            .map(w -> Map.<String, Object>of(
                "warehouse_id", w.warehouseId(),
                "name", w.name(),
                "city", w.city(),
                "country", w.country(),
                "status", w.status()
            ))
            .collect(Collectors.toList());

        return new WarehouseListResult(mapped.size(), mapped, null);
    }
}
