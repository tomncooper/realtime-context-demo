package com.smartship.api.services;

import com.smartship.api.KafkaStreamsQueryService;
import com.smartship.api.model.hybrid.*;
import com.smartship.api.model.reference.*;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for orchestrating hybrid queries that combine real-time Kafka Streams data
 * with PostgreSQL reference data.
 * Provides enriched data for LLM query capability.
 */
@ApplicationScoped
public class QueryOrchestrationService {

    private static final Logger LOG = Logger.getLogger(QueryOrchestrationService.class);

    @Inject
    KafkaStreamsQueryService streamsQuery;

    @Inject
    PostgresQueryService postgresQuery;

    // ===========================================
    // Customer-centric Hybrid Queries
    // ===========================================

    /**
     * Get comprehensive customer overview with real-time stats.
     * Combines PostgreSQL customer data with Kafka Streams shipment and order stats.
     */
    public Uni<HybridQueryResult<EnrichedCustomerOverview>> getCustomerOverview(String customerId) {
        LOG.infof("Getting customer overview for: %s", customerId);
        long startTime = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        return postgresQuery.getCustomerById(customerId)
            .onItem().transform(customer -> {
                if (customer == null) {
                    LOG.warnf("Customer not found: %s", customerId);
                    long queryTime = System.currentTimeMillis() - startTime;
                    return HybridQueryResult.hybrid(
                        (EnrichedCustomerOverview) null,
                        warnings,
                        queryTime,
                        "Customer not found: " + customerId
                    );
                }

                // Get real-time stats from Kafka Streams with error handling
                Map<String, Object> shipmentStats;
                Map<String, Object> orderStats;
                try {
                    shipmentStats = streamsQuery.getCustomerShipmentStats(customerId);
                } catch (Exception e) {
                    LOG.warnf("Could not fetch shipment stats for %s: %s", customerId, e.getMessage());
                    warnings.add("Shipment stats unavailable: Kafka Streams connection issue");
                    shipmentStats = Map.of();
                }
                try {
                    orderStats = streamsQuery.getCustomerOrderStats(customerId);
                } catch (Exception e) {
                    LOG.warnf("Could not fetch order stats for %s: %s", customerId, e.getMessage());
                    warnings.add("Order stats unavailable: Kafka Streams connection issue");
                    orderStats = Map.of();
                }

                // Build enriched overview
                EnrichedCustomerOverview.Builder builder = EnrichedCustomerOverview.builder()
                    .customerId(customer.customerId())
                    .companyName(customer.companyName())
                    .contactEmail(customer.contactEmail())
                    .slaTier(customer.slaTier())
                    .accountStatus(customer.accountStatus())
                    .lastUpdated(System.currentTimeMillis());

                // Add shipment stats if available
                if (shipmentStats != null && !shipmentStats.isEmpty()) {
                    builder.totalShipments(getLong(shipmentStats, "total_shipments", 0))
                           .deliveredShipments(getLong(shipmentStats, "delivered", 0))
                           .inTransitShipments(getLong(shipmentStats, "in_transit", 0))
                           .exceptionShipments(getLong(shipmentStats, "exceptions", 0))
                           .shipmentOnTimeRate(getDouble(shipmentStats, "on_time_rate", 0.0));
                }

                // Add order stats if available
                if (orderStats != null && !orderStats.isEmpty()) {
                    builder.totalOrders(getLong(orderStats, "total_orders", 0))
                           .pendingOrders(getLong(orderStats, "pending_orders", 0))
                           .shippedOrders(getLong(orderStats, "shipped_orders", 0))
                           .deliveredOrders(getLong(orderStats, "delivered_orders", 0))
                           .atRiskOrders(getLong(orderStats, "at_risk_orders", 0));
                }

                // Calculate SLA compliance
                long totalOrders = builder.build().totalOrders();
                long atRisk = builder.build().atRiskOrders();
                double slaCompliance = totalOrders > 0 ? (1.0 - (double) atRisk / totalOrders) * 100 : 100.0;
                builder.slaComplianceRate(slaCompliance);

                long queryTime = System.currentTimeMillis() - startTime;
                String summary = String.format("Customer %s (%s) - %d orders, %d shipments, %.1f%% SLA compliance",
                    customer.companyName(), customer.slaTier(), totalOrders, builder.build().totalShipments(), slaCompliance);

                return HybridQueryResult.hybrid(builder.build(), warnings, queryTime, summary);
            });
    }

    /**
     * Get late shipments for a company by name.
     * Finds customers by company name, then retrieves their late shipments with enrichment.
     */
    public Uni<HybridQueryResult<List<EnrichedLateShipment>>> getLateShipmentsForCompany(String companyName) {
        LOG.infof("Getting late shipments for company: %s", companyName);
        long startTime = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        return postgresQuery.findCustomersByCompanyName(companyName)
            .onItem().transform(customers -> {
                if (customers.isEmpty()) {
                    long queryTime = System.currentTimeMillis() - startTime;
                    return HybridQueryResult.hybrid(
                        Collections.<EnrichedLateShipment>emptyList(),
                        warnings,
                        queryTime,
                        "No customers found matching: " + companyName
                    );
                }

                // Get all late shipments with error handling
                List<Map<String, Object>> allLateShipments;
                try {
                    allLateShipments = streamsQuery.getAllLateShipments();
                } catch (Exception e) {
                    LOG.warnf("Could not fetch late shipments: %s", e.getMessage());
                    warnings.add("Late shipments data unavailable: Kafka Streams connection issue");
                    allLateShipments = new ArrayList<>();
                }

                // Filter to matching customer IDs
                Set<String> customerIds = customers.stream()
                    .map(CustomerDto::customerId)
                    .collect(Collectors.toSet());

                Map<String, CustomerDto> customerMap = customers.stream()
                    .collect(Collectors.toMap(CustomerDto::customerId, c -> c));

                List<EnrichedLateShipment> enrichedShipments = allLateShipments.stream()
                    .filter(s -> customerIds.contains(getString(s, "customer_id", "")))
                    .map(s -> {
                        String custId = getString(s, "customer_id", "");
                        CustomerDto customer = customerMap.get(custId);

                        return EnrichedLateShipment.builder()
                            .shipmentId(getString(s, "shipment_id", ""))
                            .customerId(custId)
                            .warehouseId(getString(s, "warehouse_id", ""))
                            .destinationCity(getString(s, "destination_city", ""))
                            .destinationCountry(getString(s, "destination_country", ""))
                            .currentStatus(getString(s, "current_status", ""))
                            .delayMinutes(getLong(s, "delay_minutes", 0))
                            .expectedDelivery(getLong(s, "expected_delivery", 0))
                            .lastUpdated(getLong(s, "last_updated", 0))
                            .companyName(customer != null ? customer.companyName() : "")
                            .slaTier(customer != null ? customer.slaTier() : "")
                            .contactEmail(customer != null ? customer.contactEmail() : "")
                            .build();
                    })
                    .collect(Collectors.toList());

                long queryTime = System.currentTimeMillis() - startTime;
                String summary = String.format("Found %d late shipments for %d customer(s) matching '%s'",
                    enrichedShipments.size(), customers.size(), companyName);

                return HybridQueryResult.hybrid(enrichedShipments, warnings, queryTime, summary);
            });
    }

    /**
     * Get customer SLA compliance details.
     */
    public Uni<HybridQueryResult<Map<String, Object>>> getCustomerSLACompliance(String customerId) {
        LOG.infof("Getting SLA compliance for customer: %s", customerId);
        long startTime = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        return postgresQuery.getCustomerById(customerId)
            .onItem().transform(customer -> {
                if (customer == null) {
                    long queryTime = System.currentTimeMillis() - startTime;
                    return HybridQueryResult.<Map<String, Object>>hybrid(
                        Map.of("error", "Customer not found: " + customerId),
                        warnings,
                        queryTime,
                        "Customer not found"
                    );
                }

                // Get order stats and at-risk orders with error handling
                Map<String, Object> orderStats;
                List<Map<String, Object>> allAtRisk;
                try {
                    orderStats = streamsQuery.getCustomerOrderStats(customerId);
                } catch (Exception e) {
                    LOG.warnf("Could not fetch order stats for %s: %s", customerId, e.getMessage());
                    warnings.add("Order stats unavailable: Kafka Streams connection issue");
                    orderStats = Map.of();
                }
                try {
                    allAtRisk = streamsQuery.getOrdersAtSLARisk();
                } catch (Exception e) {
                    LOG.warnf("Could not fetch at-risk orders: %s", e.getMessage());
                    warnings.add("At-risk orders data unavailable: Kafka Streams connection issue");
                    allAtRisk = new ArrayList<>();
                }

                // Filter at-risk orders for this customer
                List<Map<String, Object>> customerAtRisk = allAtRisk.stream()
                    .filter(o -> customerId.equals(getString(o, "customer_id", "")))
                    .collect(Collectors.toList());

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("customer_id", customerId);
                result.put("company_name", customer.companyName());
                result.put("sla_tier", customer.slaTier());
                result.put("order_stats", orderStats != null ? orderStats : Map.of());
                result.put("at_risk_orders_count", customerAtRisk.size());
                result.put("at_risk_orders", customerAtRisk);

                // Calculate compliance rate
                long totalOrders = orderStats != null ? getLong(orderStats, "total_orders", 0) : 0;
                double complianceRate = totalOrders > 0 ?
                    (1.0 - (double) customerAtRisk.size() / totalOrders) * 100 : 100.0;
                result.put("sla_compliance_rate", complianceRate);

                long queryTime = System.currentTimeMillis() - startTime;
                String summary = String.format("Customer %s SLA compliance: %.1f%% (%d at-risk orders)",
                    customer.companyName(), complianceRate, customerAtRisk.size());

                return HybridQueryResult.hybrid(result, warnings, queryTime, summary);
            });
    }

    // ===========================================
    // Vehicle/Logistics Hybrid Queries
    // ===========================================

    /**
     * Get enriched vehicle state with reference data.
     */
    public Uni<HybridQueryResult<EnrichedVehicleState>> getEnrichedVehicleState(String vehicleId) {
        LOG.infof("Getting enriched vehicle state for: %s", vehicleId);
        long startTime = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        // Get real-time state first with error handling
        Map<String, Object> vehicleState;
        try {
            vehicleState = streamsQuery.getVehicleState(vehicleId);
        } catch (Exception e) {
            LOG.warnf("Could not fetch vehicle state for %s: %s", vehicleId, e.getMessage());
            long queryTime = System.currentTimeMillis() - startTime;
            return Uni.createFrom().item(HybridQueryResult.hybrid(
                null, List.of("Vehicle state unavailable: Kafka Streams connection issue"),
                queryTime, "Vehicle state unavailable: " + vehicleId));
        }
        if (vehicleState == null || vehicleState.isEmpty()) {
            long queryTime = System.currentTimeMillis() - startTime;
            return Uni.createFrom().item(HybridQueryResult.hybrid(
                null, warnings, queryTime, "Vehicle not found in real-time state: " + vehicleId));
        }

        // Get reference data
        return postgresQuery.getVehicleById(vehicleId)
            .onItem().transformToUni(vehicle -> {
                if (vehicle == null) {
                    long queryTime = System.currentTimeMillis() - startTime;
                    return Uni.createFrom().item(HybridQueryResult.<EnrichedVehicleState>hybrid(
                        null, warnings, queryTime, "Vehicle not found in reference data: " + vehicleId));
                }

                // Get warehouse info
                return postgresQuery.getWarehouseById(vehicle.homeWarehouseId())
                    .onItem().transformToUni(warehouse -> {
                        // Find driver assigned to this vehicle
                        return postgresQuery.getAllDrivers()
                            .onItem().transform(drivers -> {
                                DriverDto driver = drivers.stream()
                                    .filter(d -> vehicleId.equals(d.assignedVehicleId()))
                                    .findFirst()
                                    .orElse(null);

                                double currentLoadKg = getDouble(vehicleState, "current_load_kg", 0);
                                double loadPercentage = vehicle.capacityKg() > 0 ?
                                    (currentLoadKg / vehicle.capacityKg()) * 100 : 0;

                                EnrichedVehicleState enriched = EnrichedVehicleState.builder()
                                    .vehicleId(vehicleId)
                                    .status(getString(vehicleState, "status", ""))
                                    .latitude(getDouble(vehicleState, "latitude", 0))
                                    .longitude(getDouble(vehicleState, "longitude", 0))
                                    .speedKmh(getDouble(vehicleState, "speed_kmh", 0))
                                    .heading(getDouble(vehicleState, "heading", 0))
                                    .fuelLevelPercent(getDouble(vehicleState, "fuel_level_percent", 0))
                                    .currentLoadKg(currentLoadKg)
                                    .currentLoadVolumeM3(getDouble(vehicleState, "current_load_volume_m3", 0))
                                    .currentShipmentId(getString(vehicleState, "current_shipment_id", ""))
                                    .telemetryTimestamp(getLong(vehicleState, "timestamp", 0))
                                    .vehicleType(vehicle.vehicleType())
                                    .licensePlate(vehicle.licensePlate())
                                    .capacityKg(vehicle.capacityKg())
                                    .capacityCubicM(vehicle.capacityCubicM())
                                    .homeWarehouseId(vehicle.homeWarehouseId())
                                    .fuelType(vehicle.fuelType())
                                    .driverId(driver != null ? driver.driverId() : null)
                                    .driverName(driver != null ? driver.firstName() + " " + driver.lastName() : null)
                                    .licenseType(driver != null ? driver.licenseType() : null)
                                    .certifications(driver != null ? driver.certifications() : null)
                                    .homeWarehouseName(warehouse != null ? warehouse.name() : null)
                                    .homeWarehouseCity(warehouse != null ? warehouse.city() : null)
                                    .loadPercentage(loadPercentage)
                                    .lastUpdated(System.currentTimeMillis())
                                    .build();

                                long queryTime = System.currentTimeMillis() - startTime;
                                String summary = String.format("Vehicle %s (%s) - %s, %.1f%% loaded, driver: %s",
                                    vehicleId, vehicle.vehicleType(), getString(vehicleState, "status", "UNKNOWN"),
                                    loadPercentage, enriched.driverName() != null ? enriched.driverName() : "unassigned");

                                return HybridQueryResult.hybrid(enriched, warnings, queryTime, summary);
                            });
                    });
            });
    }

    /**
     * Get driver with current vehicle state.
     */
    public Uni<HybridQueryResult<Map<String, Object>>> getDriverWithVehicleState(String driverId) {
        LOG.infof("Getting driver tracking info for: %s", driverId);
        long startTime = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        return postgresQuery.getDriverById(driverId)
            .onItem().transform(driver -> {
                if (driver == null) {
                    long queryTime = System.currentTimeMillis() - startTime;
                    return HybridQueryResult.<Map<String, Object>>hybrid(
                        Map.of("error", "Driver not found: " + driverId),
                        warnings,
                        queryTime,
                        "Driver not found"
                    );
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("driver_id", driver.driverId());
                result.put("driver_name", driver.firstName() + " " + driver.lastName());
                result.put("license_type", driver.licenseType());
                result.put("certifications", driver.certifications());
                result.put("status", driver.status());

                // Get vehicle state if assigned with error handling
                if (driver.assignedVehicleId() != null) {
                    result.put("assigned_vehicle_id", driver.assignedVehicleId());
                    Map<String, Object> vehicleState = null;
                    try {
                        vehicleState = streamsQuery.getVehicleState(driver.assignedVehicleId());
                    } catch (Exception e) {
                        LOG.warnf("Could not fetch vehicle state for driver %s: %s", driverId, e.getMessage());
                        warnings.add("Vehicle state unavailable: Kafka Streams connection issue");
                    }
                    if (vehicleState != null && !vehicleState.isEmpty()) {
                        result.put("vehicle_state", vehicleState);
                        result.put("vehicle_location", Map.of(
                            "latitude", getDouble(vehicleState, "latitude", 0),
                            "longitude", getDouble(vehicleState, "longitude", 0)
                        ));
                    }
                }

                long queryTime = System.currentTimeMillis() - startTime;
                String summary = String.format("Driver %s %s - %s, vehicle: %s",
                    driver.firstName(), driver.lastName(), driver.status(),
                    driver.assignedVehicleId() != null ? driver.assignedVehicleId() : "unassigned");

                return HybridQueryResult.hybrid(result, warnings, queryTime, summary);
            });
    }

    // ===========================================
    // Warehouse Hybrid Queries
    // ===========================================

    /**
     * Get warehouse operational status with real-time metrics.
     */
    public Uni<HybridQueryResult<Map<String, Object>>> getWarehouseOperationalStatus(String warehouseId) {
        LOG.infof("Getting warehouse operational status for: %s", warehouseId);
        long startTime = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        return postgresQuery.getWarehouseById(warehouseId)
            .onItem().transformToUni(warehouse -> {
                if (warehouse == null) {
                    long queryTime = System.currentTimeMillis() - startTime;
                    return Uni.createFrom().item(HybridQueryResult.<Map<String, Object>>hybrid(
                        Map.of("error", "Warehouse not found: " + warehouseId),
                        warnings,
                        queryTime,
                        "Warehouse not found"
                    ));
                }

                // Get vehicles and drivers for this warehouse
                return postgresQuery.findVehiclesByHomeWarehouse(warehouseId)
                    .onItem().transformToUni(vehicles ->
                        postgresQuery.findDriversByHomeWarehouse(warehouseId)
                            .onItem().transform(drivers -> {
                                // Get real-time metrics with graceful fallback
                                List<Map<String, Object>> metrics;
                                List<Map<String, Object>> performance;
                                try {
                                    metrics = streamsQuery.getWarehouseMetrics(warehouseId);
                                } catch (Exception e) {
                                    LOG.warnf("Could not fetch warehouse metrics for %s: %s", warehouseId, e.getMessage());
                                    warnings.add("Real-time metrics unavailable: Kafka Streams connection issue");
                                    metrics = new ArrayList<>();
                                }
                                try {
                                    performance = streamsQuery.getHourlyPerformance(warehouseId);
                                } catch (Exception e) {
                                    LOG.warnf("Could not fetch hourly performance for %s: %s", warehouseId, e.getMessage());
                                    warnings.add("Hourly performance unavailable: Kafka Streams connection issue");
                                    performance = new ArrayList<>();
                                }

                                Map<String, Object> result = new LinkedHashMap<>();
                                result.put("warehouse_id", warehouseId);
                                result.put("name", warehouse.name());
                                result.put("city", warehouse.city());
                                result.put("country", warehouse.country());
                                result.put("status", warehouse.status());
                                result.put("location", Map.of(
                                    "latitude", warehouse.latitude(),
                                    "longitude", warehouse.longitude()
                                ));

                                // Vehicle summary
                                long activeVehicles = vehicles.stream()
                                    .filter(v -> "ACTIVE".equals(v.status()) || "DELIVERING".equals(v.status()))
                                    .count();
                                result.put("total_vehicles", vehicles.size());
                                result.put("active_vehicles", activeVehicles);

                                // Driver summary
                                long availableDrivers = drivers.stream()
                                    .filter(d -> "AVAILABLE".equals(d.status()))
                                    .count();
                                result.put("total_drivers", drivers.size());
                                result.put("available_drivers", availableDrivers);

                                // Real-time metrics
                                result.put("realtime_metrics", metrics);
                                result.put("hourly_performance", performance);

                                long queryTime = System.currentTimeMillis() - startTime;
                                String summary = String.format("Warehouse %s (%s) - %d vehicles, %d drivers, status: %s",
                                    warehouse.name(), warehouse.city(), vehicles.size(), drivers.size(), warehouse.status());

                                return HybridQueryResult.hybrid(result, warnings, queryTime, summary);
                            })
                    );
            });
    }

    // ===========================================
    // Order Hybrid Queries
    // ===========================================

    /**
     * Get order details with related shipment information.
     */
    public Uni<HybridQueryResult<Map<String, Object>>> getOrderWithShipments(String orderId) {
        LOG.infof("Getting order details with shipments for: %s", orderId);
        long startTime = System.currentTimeMillis();
        List<String> warnings = new ArrayList<>();

        // Get order state from Kafka Streams with error handling
        Map<String, Object> orderState;
        try {
            orderState = streamsQuery.getOrderState(orderId);
        } catch (Exception e) {
            LOG.warnf("Could not fetch order state for %s: %s", orderId, e.getMessage());
            long queryTime = System.currentTimeMillis() - startTime;
            return Uni.createFrom().item(HybridQueryResult.hybrid(
                Map.of("error", "Order state unavailable: " + orderId),
                List.of("Order state unavailable: Kafka Streams connection issue"),
                queryTime,
                "Order state unavailable"
            ));
        }
        if (orderState == null || orderState.isEmpty()) {
            long queryTime = System.currentTimeMillis() - startTime;
            return Uni.createFrom().item(HybridQueryResult.hybrid(
                Map.of("error", "Order not found: " + orderId),
                warnings,
                queryTime,
                "Order not found"
            ));
        }

        String customerId = getString(orderState, "customer_id", "");

        return postgresQuery.getCustomerById(customerId)
            .onItem().transform(customer -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("order_id", orderId);
                result.put("order_state", orderState);

                // Add customer info if found
                if (customer != null) {
                    result.put("customer", Map.of(
                        "customer_id", customer.customerId(),
                        "company_name", customer.companyName(),
                        "sla_tier", customer.slaTier(),
                        "contact_email", customer.contactEmail()
                    ));
                }

                // Get SLA tracking info with error handling
                Map<String, Object> slaTracking = null;
                try {
                    slaTracking = streamsQuery.getOrderSLATracking(orderId);
                } catch (Exception e) {
                    LOG.warnf("Could not fetch SLA tracking for %s: %s", orderId, e.getMessage());
                    warnings.add("SLA tracking data unavailable: Kafka Streams connection issue");
                }
                if (slaTracking != null && !slaTracking.isEmpty()) {
                    result.put("sla_tracking", slaTracking);
                }

                long queryTime = System.currentTimeMillis() - startTime;
                String status = getString(orderState, "status", "UNKNOWN");
                String summary = String.format("Order %s - Status: %s, Customer: %s",
                    orderId, status, customer != null ? customer.companyName() : customerId);

                return HybridQueryResult.hybrid(result, warnings, queryTime, summary);
            });
    }

    // ===========================================
    // Helper Methods
    // ===========================================

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private long getLong(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
