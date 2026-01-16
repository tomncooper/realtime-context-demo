package com.smartship.api.services;

import com.smartship.api.KafkaStreamsQueryService;
import com.smartship.api.model.hybrid.EnrichedCustomerOverview;
import com.smartship.api.model.hybrid.HybridQueryResult;
import com.smartship.api.model.reference.CustomerDto;
import com.smartship.api.model.reference.DriverDto;
import com.smartship.api.model.reference.RouteDto;
import com.smartship.api.model.reference.WarehouseDto;
import com.smartship.api.model.tools.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared service containing business logic for tool operations.
 * This service is used by both LangChain4j tools and MCP tools to avoid code duplication.
 *
 * <p>All methods return structured result objects that can be:
 * <ul>
 *   <li>Serialized to JSON by LangChain4j tools</li>
 *   <li>Returned directly by MCP tools (Quarkus MCP handles serialization)</li>
 * </ul>
 */
@ApplicationScoped
public class ToolOperationsService {

    private static final Logger LOG = Logger.getLogger(ToolOperationsService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_LIMIT = 10;
    private static final int VEHICLE_LIMIT = 20;

    @Inject
    KafkaStreamsQueryService streamsQueryService;

    @Inject
    PostgresQueryService postgresQueryService;

    @Inject
    QueryOrchestrationService orchestrationService;

    // ========================================================================
    // ID Normalization Helpers
    // ========================================================================

    public String normalizeCustomerId(String customerId) {
        if (customerId == null || customerId.isBlank()) return null;
        String normalized = customerId.toUpperCase().trim();
        if (!normalized.startsWith("CUST-")) {
            try {
                int id = Integer.parseInt(normalized.replaceAll("[^0-9]", ""));
                normalized = String.format("CUST-%04d", id);
            } catch (NumberFormatException e) {
                normalized = "CUST-" + normalized;
            }
        }
        return normalized;
    }

    public String normalizeVehicleId(String vehicleId) {
        if (vehicleId == null || vehicleId.isBlank()) return null;
        String normalized = vehicleId.toUpperCase().trim();
        if (!normalized.startsWith("VEH-")) {
            try {
                int id = Integer.parseInt(normalized.replaceAll("[^0-9]", ""));
                normalized = String.format("VEH-%03d", id);
            } catch (NumberFormatException e) {
                normalized = "VEH-" + normalized;
            }
        }
        return normalized;
    }

    public String normalizeWarehouseId(String warehouseId) {
        if (warehouseId == null || warehouseId.isBlank()) return null;
        String normalized = warehouseId.toUpperCase().trim();
        if (!normalized.startsWith("WH-")) {
            normalized = "WH-" + normalized;
        }
        return normalized;
    }

    public String normalizeOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) return null;
        String normalized = orderId.toUpperCase().trim();
        if (!normalized.startsWith("ORD-")) {
            try {
                int id = Integer.parseInt(normalized.replaceAll("[^0-9]", ""));
                normalized = String.format("ORD-%05d", id);
            } catch (NumberFormatException e) {
                normalized = "ORD-" + normalized;
            }
        }
        return normalized;
    }

    // ========================================================================
    // Shipment Operations
    // ========================================================================

    /**
     * Get counts for all shipment statuses.
     */
    public ShipmentStatusResult getShipmentStatusCounts() {
        LOG.debug("ToolOperationsService: getShipmentStatusCounts");
        try {
            Map<String, Object> counts = streamsQueryService.getAllStatusCounts();
            if (counts == null || counts.isEmpty()) {
                return ShipmentStatusResult.empty();
            }

            Map<String, Long> longCounts = counts.entrySet().stream()
                .filter(e -> e.getValue() instanceof Number)
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> ((Number) e.getValue()).longValue()
                ));

            return ShipmentStatusResult.of(longCounts);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting shipment status counts");
            throw new ToolOperationException("Failed to retrieve shipment status counts", e);
        }
    }

    /**
     * Get late shipments with a limit on results.
     */
    public LateShipmentsResult getLateShipments() {
        return getLateShipments(DEFAULT_LIMIT);
    }

    public LateShipmentsResult getLateShipments(int limit) {
        LOG.debugf("ToolOperationsService: getLateShipments (limit=%d)", limit);
        try {
            List<Map<String, Object>> lateShipments = streamsQueryService.getAllLateShipments();
            if (lateShipments == null || lateShipments.isEmpty()) {
                return LateShipmentsResult.empty();
            }
            return LateShipmentsResult.of(lateShipments, limit);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting late shipments");
            throw new ToolOperationException("Failed to retrieve late shipments", e);
        }
    }

    /**
     * Get count of shipments for a specific status.
     */
    public ShipmentByStatusResult getShipmentByStatus(String status) {
        LOG.debugf("ToolOperationsService: getShipmentByStatus (status=%s)", status);
        if (status == null || status.isBlank()) {
            return ShipmentByStatusResult.notFound("UNKNOWN");
        }

        String normalizedStatus = status.toUpperCase().trim();
        try {
            Long count = streamsQueryService.getShipmentCountByStatus(normalizedStatus);
            if (count == null || count == 0) {
                return ShipmentByStatusResult.notFound(normalizedStatus);
            }
            return ShipmentByStatusResult.of(normalizedStatus, count);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting shipment count for status: %s", normalizedStatus);
            throw new ToolOperationException("Failed to retrieve shipment count for status: " + normalizedStatus, e);
        }
    }

    /**
     * Get shipment statistics for a customer.
     */
    public CustomerShipmentStatsResult getCustomerShipmentStats(String customerId) {
        String normalizedId = normalizeCustomerId(customerId);
        LOG.debugf("ToolOperationsService: getCustomerShipmentStats (customerId=%s)", normalizedId);

        if (normalizedId == null) {
            return CustomerShipmentStatsResult.notFound("UNKNOWN");
        }

        try {
            Map<String, Object> stats = streamsQueryService.getCustomerShipmentStats(normalizedId);
            if (stats == null || stats.isEmpty()) {
                return CustomerShipmentStatsResult.notFound(normalizedId);
            }
            return CustomerShipmentStatsResult.of(normalizedId, stats);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting customer shipment stats for: %s", normalizedId);
            throw new ToolOperationException("Failed to retrieve shipment stats for customer: " + normalizedId, e);
        }
    }

    // ========================================================================
    // Vehicle Operations
    // ========================================================================

    /**
     * Get real-time state for a specific vehicle.
     */
    public VehicleStateResult getVehicleState(String vehicleId) {
        String normalizedId = normalizeVehicleId(vehicleId);
        LOG.debugf("ToolOperationsService: getVehicleState (vehicleId=%s)", normalizedId);

        if (normalizedId == null) {
            return VehicleStateResult.notFound("UNKNOWN");
        }

        try {
            Map<String, Object> state = streamsQueryService.getVehicleState(normalizedId);
            if (state == null || state.isEmpty()) {
                return VehicleStateResult.notFound(normalizedId);
            }
            return VehicleStateResult.of(normalizedId, state);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting vehicle state for: %s", normalizedId);
            throw new ToolOperationException("Failed to retrieve vehicle state for: " + normalizedId, e);
        }
    }

    /**
     * Get fleet-wide vehicle status overview.
     */
    public FleetStatusResult getAllVehicleStates() {
        return getAllVehicleStates(VEHICLE_LIMIT);
    }

    public FleetStatusResult getAllVehicleStates(int limit) {
        LOG.debugf("ToolOperationsService: getAllVehicleStates (limit=%d)", limit);
        try {
            List<Map<String, Object>> vehicles = streamsQueryService.getAllVehicleStates();
            if (vehicles == null || vehicles.isEmpty()) {
                return FleetStatusResult.empty();
            }

            Map<String, Long> statusCounts = vehicles.stream()
                .filter(v -> v.get("status") != null)
                .collect(Collectors.groupingBy(
                    v -> String.valueOf(v.get("status")),
                    Collectors.counting()
                ));

            return FleetStatusResult.of(vehicles, statusCounts, limit);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting all vehicle states");
            throw new ToolOperationException("Failed to retrieve vehicle states", e);
        }
    }

    /**
     * Get fleet utilization statistics.
     */
    public FleetUtilizationResult getFleetUtilization() {
        LOG.debug("ToolOperationsService: getFleetUtilization");
        try {
            List<Map<String, Object>> vehicles = streamsQueryService.getAllVehicleStates();
            if (vehicles == null || vehicles.isEmpty()) {
                return FleetUtilizationResult.empty();
            }

            Map<String, Long> statusCounts = vehicles.stream()
                .filter(v -> v.get("status") != null)
                .collect(Collectors.groupingBy(
                    v -> String.valueOf(v.get("status")),
                    Collectors.counting()
                ));

            double avgLoadKg = vehicles.stream()
                .filter(v -> v.get("current_load_kg") != null)
                .mapToDouble(v -> ((Number) v.get("current_load_kg")).doubleValue())
                .average()
                .orElse(0.0);

            double avgFuelLevel = vehicles.stream()
                .filter(v -> v.get("fuel_level_percent") != null)
                .mapToDouble(v -> ((Number) v.get("fuel_level_percent")).doubleValue())
                .average()
                .orElse(0.0);

            long activeVehicles = statusCounts.getOrDefault("EN_ROUTE", 0L) +
                                  statusCounts.getOrDefault("LOADING", 0L) +
                                  statusCounts.getOrDefault("UNLOADING", 0L);
            long idleVehicles = statusCounts.getOrDefault("IDLE", 0L);
            long maintenanceVehicles = statusCounts.getOrDefault("MAINTENANCE", 0L);
            int totalVehicles = vehicles.size();
            double utilizationRate = totalVehicles > 0 ? (activeVehicles * 100.0 / totalVehicles) : 0.0;

            return FleetUtilizationResult.of(
                totalVehicles, statusCounts, activeVehicles, idleVehicles, maintenanceVehicles,
                utilizationRate, avgLoadKg, avgFuelLevel
            );
        } catch (Exception e) {
            LOG.errorf(e, "Error calculating fleet utilization");
            throw new ToolOperationException("Failed to calculate fleet utilization", e);
        }
    }

    // ========================================================================
    // Customer Operations
    // ========================================================================

    /**
     * Get comprehensive customer overview with real-time stats.
     */
    public HybridQueryResult<EnrichedCustomerOverview> getCustomerOverview(String customerId) {
        String normalizedId = normalizeCustomerId(customerId);
        LOG.debugf("ToolOperationsService: getCustomerOverview (customerId=%s)", normalizedId);

        if (normalizedId == null) {
            return HybridQueryResult.of(null, List.of(), 0, "Invalid customer ID format");
        }

        try {
            return orchestrationService.getCustomerOverview(normalizedId)
                .await().atMost(TIMEOUT);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting customer overview for: %s", normalizedId);
            throw new ToolOperationException("Failed to retrieve customer overview for: " + normalizedId, e);
        }
    }

    /**
     * Get customer SLA compliance metrics.
     */
    public HybridQueryResult<Map<String, Object>> getCustomerSlaCompliance(String customerId) {
        String normalizedId = normalizeCustomerId(customerId);
        LOG.debugf("ToolOperationsService: getCustomerSlaCompliance (customerId=%s)", normalizedId);

        if (normalizedId == null) {
            return HybridQueryResult.of(null, List.of(), 0, "Invalid customer ID format");
        }

        try {
            return orchestrationService.getCustomerSLACompliance(normalizedId)
                .await().atMost(TIMEOUT);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting customer SLA compliance for: %s", normalizedId);
            throw new ToolOperationException("Failed to retrieve SLA compliance for: " + normalizedId, e);
        }
    }

    /**
     * Find customers by company name (partial match).
     */
    public CustomerSearchResult findCustomersByCompanyName(String companyName) {
        return findCustomersByCompanyName(companyName, DEFAULT_LIMIT);
    }

    public CustomerSearchResult findCustomersByCompanyName(String companyName, int limit) {
        LOG.debugf("ToolOperationsService: findCustomersByCompanyName (name=%s, limit=%d)", companyName, limit);

        if (companyName == null || companyName.isBlank()) {
            return CustomerSearchResult.empty("");
        }

        try {
            List<CustomerDto> customers = postgresQueryService
                .findCustomersByCompanyName(companyName)
                .await().atMost(TIMEOUT);

            if (customers == null || customers.isEmpty()) {
                return CustomerSearchResult.empty(companyName);
            }

            return CustomerSearchResult.of(companyName, customers, limit);
        } catch (Exception e) {
            LOG.errorf(e, "Error finding customers by name: %s", companyName);
            throw new ToolOperationException("Failed to search customers: " + companyName, e);
        }
    }

    // ========================================================================
    // Order Operations
    // ========================================================================

    /**
     * Get orders at SLA risk.
     */
    public OrdersAtRiskResult getOrdersAtSlaRisk() {
        return getOrdersAtSlaRisk(DEFAULT_LIMIT);
    }

    public OrdersAtRiskResult getOrdersAtSlaRisk(int limit) {
        LOG.debugf("ToolOperationsService: getOrdersAtSlaRisk (limit=%d)", limit);
        try {
            List<Map<String, Object>> atRiskOrders = streamsQueryService.getOrdersAtSLARisk();
            if (atRiskOrders == null || atRiskOrders.isEmpty()) {
                return OrdersAtRiskResult.empty();
            }
            return OrdersAtRiskResult.of(atRiskOrders, limit);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting orders at SLA risk");
            throw new ToolOperationException("Failed to retrieve orders at SLA risk", e);
        }
    }

    /**
     * Get current state of a specific order.
     */
    public OrderStateResult getOrderState(String orderId) {
        String normalizedId = normalizeOrderId(orderId);
        LOG.debugf("ToolOperationsService: getOrderState (orderId=%s)", normalizedId);

        if (normalizedId == null) {
            return OrderStateResult.notFound("UNKNOWN");
        }

        try {
            Map<String, Object> state = streamsQueryService.getOrderState(normalizedId);
            if (state == null || state.isEmpty()) {
                return OrderStateResult.notFound(normalizedId);
            }
            return OrderStateResult.of(normalizedId, state);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting order state for: %s", normalizedId);
            throw new ToolOperationException("Failed to retrieve order state for: " + normalizedId, e);
        }
    }

    /**
     * Get order statistics for a customer.
     */
    public CustomerOrderStatsResult getCustomerOrderStats(String customerId) {
        String normalizedId = normalizeCustomerId(customerId);
        LOG.debugf("ToolOperationsService: getCustomerOrderStats (customerId=%s)", normalizedId);

        if (normalizedId == null) {
            return CustomerOrderStatsResult.notFound("UNKNOWN");
        }

        try {
            Map<String, Object> stats = streamsQueryService.getCustomerOrderStats(normalizedId);
            if (stats == null || stats.isEmpty()) {
                return CustomerOrderStatsResult.notFound(normalizedId);
            }
            return CustomerOrderStatsResult.of(normalizedId, stats);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting customer order stats for: %s", normalizedId);
            throw new ToolOperationException("Failed to retrieve order stats for customer: " + normalizedId, e);
        }
    }

    // ========================================================================
    // Warehouse Operations
    // ========================================================================

    /**
     * Get list of all warehouses.
     */
    public WarehouseListResult getWarehouseList() {
        LOG.debug("ToolOperationsService: getWarehouseList");
        try {
            List<WarehouseDto> warehouses = postgresQueryService.getAllWarehouses()
                .await().atMost(TIMEOUT);

            if (warehouses == null || warehouses.isEmpty()) {
                return WarehouseListResult.empty();
            }

            return WarehouseListResult.of(warehouses);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting warehouse list");
            throw new ToolOperationException("Failed to retrieve warehouse list", e);
        }
    }

    /**
     * Get operational status for a specific warehouse.
     */
    public HybridQueryResult<Map<String, Object>> getWarehouseStatus(String warehouseId) {
        String normalizedId = normalizeWarehouseId(warehouseId);
        LOG.debugf("ToolOperationsService: getWarehouseStatus (warehouseId=%s)", normalizedId);

        if (normalizedId == null) {
            return HybridQueryResult.of(null, List.of(), 0, "Invalid warehouse ID format");
        }

        try {
            return orchestrationService.getWarehouseOperationalStatus(normalizedId)
                .await().atMost(TIMEOUT);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting warehouse status for: %s", normalizedId);
            throw new ToolOperationException("Failed to retrieve warehouse status for: " + normalizedId, e);
        }
    }

    /**
     * Get hourly performance metrics for a warehouse.
     */
    public WarehousePerformanceResult getWarehousePerformance(String warehouseId) {
        String normalizedId = normalizeWarehouseId(warehouseId);
        LOG.debugf("ToolOperationsService: getWarehousePerformance (warehouseId=%s)", normalizedId);

        if (normalizedId == null) {
            return WarehousePerformanceResult.empty("UNKNOWN");
        }

        try {
            List<Map<String, Object>> windows = streamsQueryService.getHourlyPerformance(normalizedId);
            if (windows == null || windows.isEmpty()) {
                return WarehousePerformanceResult.empty(normalizedId);
            }
            return WarehousePerformanceResult.of(normalizedId, windows);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting warehouse performance for: %s", normalizedId);
            throw new ToolOperationException("Failed to retrieve warehouse performance for: " + normalizedId, e);
        }
    }

    // ========================================================================
    // Reference Data Operations
    // ========================================================================

    /**
     * Get available drivers.
     */
    public AvailableDriversResult getAvailableDrivers() {
        LOG.debug("ToolOperationsService: getAvailableDrivers");
        try {
            List<DriverDto> drivers = postgresQueryService.findAvailableDrivers()
                .await().atMost(TIMEOUT);

            if (drivers == null || drivers.isEmpty()) {
                return AvailableDriversResult.empty();
            }

            return AvailableDriversResult.of(drivers);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting available drivers");
            throw new ToolOperationException("Failed to retrieve available drivers", e);
        }
    }

    /**
     * Get routes from a warehouse.
     */
    public RoutesByOriginResult getRoutesByOrigin(String warehouseId) {
        String normalizedId = normalizeWarehouseId(warehouseId);
        LOG.debugf("ToolOperationsService: getRoutesByOrigin (warehouseId=%s)", normalizedId);

        if (normalizedId == null) {
            return RoutesByOriginResult.empty("UNKNOWN");
        }

        try {
            List<RouteDto> routes = postgresQueryService.findRoutesByOrigin(normalizedId)
                .await().atMost(TIMEOUT);

            if (routes == null || routes.isEmpty()) {
                return RoutesByOriginResult.empty(normalizedId);
            }

            return RoutesByOriginResult.of(normalizedId, routes);
        } catch (Exception e) {
            LOG.errorf(e, "Error getting routes from warehouse: %s", normalizedId);
            throw new ToolOperationException("Failed to retrieve routes from warehouse: " + normalizedId, e);
        }
    }

    // ========================================================================
    // Exception class for tool operations
    // ========================================================================

    public static class ToolOperationException extends RuntimeException {
        public ToolOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
