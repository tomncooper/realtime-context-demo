package com.smartship.api.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartship.api.model.reference.ProductDto;
import com.smartship.api.model.tools.AvailableDriversResult;
import com.smartship.api.model.tools.RoutesByOriginResult;
import com.smartship.api.services.PostgresQueryService;
import com.smartship.api.services.ToolOperationsService;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LangChain4j tools for querying reference data from PostgreSQL.
 *
 * <p>These tools provide access to product catalog, driver information,
 * and route data stored in the PostgreSQL database.</p>
 */
@ApplicationScoped
public class ReferenceDataTools {

    private static final Logger LOG = Logger.getLogger(ReferenceDataTools.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Inject
    ToolOperationsService operations;

    @Inject
    PostgresQueryService postgresQuery;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Get products by category.
     */
    @Tool("Get products from the catalog by category. Available categories include: Electronics, Home & Garden, Sports & Outdoors, Clothing & Accessories, Food & Beverages. Returns product details including SKU, name, weight, dimensions, and unit price. Limited to 20 results.")
    public String getProductsByCategory(String category) {
        LOG.infof("Tool called: getProductsByCategory for: %s", category);

        if (category == null || category.isBlank()) {
            return "{\"error\": \"Category is required. Examples: Electronics, Home & Garden, Sports & Outdoors\"}";
        }

        try {
            List<ProductDto> products = postgresQuery
                .getProductsByCategory(category, 20, 0)
                .await().atMost(TIMEOUT);

            if (products == null || products.isEmpty()) {
                return toJson(Map.of(
                    "message", "No products found in category: " + category,
                    "category", category,
                    "count", 0,
                    "hint", "Try categories like: Electronics, Home & Garden, Sports & Outdoors, Clothing & Accessories, Food & Beverages"
                ));
            }

            List<Map<String, Object>> productList = products.stream()
                .map(p -> Map.<String, Object>of(
                    "product_id", p.productId(),
                    "sku", p.sku(),
                    "name", p.name(),
                    "category", p.category(),
                    "weight_kg", p.weightKg(),
                    "unit_price", p.unitPrice()
                ))
                .collect(Collectors.toList());

            return toJson(Map.of(
                "category", category,
                "count", products.size(),
                "products", productList,
                "note", products.size() >= 20 ? "Showing first 20 products. More may be available." : null
            ));

        } catch (Exception e) {
            LOG.errorf(e, "Error getting products by category: %s", category);
            return "{\"error\": \"Failed to retrieve products: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Search products by name or SKU.
     */
    @Tool("Search products by name or SKU using partial match. For example, searching for 'laptop' will find products with 'laptop' in their name. Returns matching products with details. Limited to 15 results.")
    public String searchProducts(String searchTerm) {
        LOG.infof("Tool called: searchProducts for: %s", searchTerm);

        if (searchTerm == null || searchTerm.isBlank()) {
            return "{\"error\": \"Search term is required\"}";
        }

        try {
            List<ProductDto> products = postgresQuery
                .searchProducts(searchTerm, 15)
                .await().atMost(TIMEOUT);

            if (products == null || products.isEmpty()) {
                return toJson(Map.of(
                    "message", "No products found matching: " + searchTerm,
                    "search_term", searchTerm,
                    "count", 0
                ));
            }

            List<Map<String, Object>> productList = products.stream()
                .map(p -> Map.<String, Object>of(
                    "product_id", p.productId(),
                    "sku", p.sku(),
                    "name", p.name(),
                    "category", p.category(),
                    "weight_kg", p.weightKg(),
                    "unit_price", p.unitPrice()
                ))
                .collect(Collectors.toList());

            return toJson(Map.of(
                "search_term", searchTerm,
                "count", products.size(),
                "products", productList
            ));

        } catch (Exception e) {
            LOG.errorf(e, "Error searching products: %s", searchTerm);
            return "{\"error\": \"Failed to search products: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get available drivers.
     */
    @Tool("Get all drivers that are currently available (status = AVAILABLE). Returns driver details including name, license type, certifications, and assigned warehouse. Useful for finding drivers for new assignments.")
    public String getAvailableDrivers() {
        LOG.info("Tool called: getAvailableDrivers");

        try {
            AvailableDriversResult result = operations.getAvailableDrivers();
            return toJson(result);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting available drivers");
            return "{\"error\": \"Failed to retrieve available drivers: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Get routes by origin warehouse.
     */
    @Tool("Get all delivery routes originating from a specific warehouse. Warehouse ID format is WH-XXX (e.g., WH-RTM, WH-FRA). Returns route details including destination city, distance in km, estimated hours, and route type.")
    public String getRoutesByOrigin(String warehouseId) {
        LOG.infof("Tool called: getRoutesByOrigin for: %s", warehouseId);

        if (warehouseId == null || warehouseId.isBlank()) {
            return "{\"error\": \"Warehouse ID is required. Format: WH-XXX (e.g., WH-RTM)\"}";
        }

        try {
            RoutesByOriginResult result = operations.getRoutesByOrigin(warehouseId);
            return toJson(result);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting routes for warehouse: %s", warehouseId);
            return "{\"error\": \"Failed to retrieve routes: " + e.getMessage() + "\"}";
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
