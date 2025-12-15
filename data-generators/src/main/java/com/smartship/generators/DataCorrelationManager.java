package com.smartship.generators;

import com.smartship.generators.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Central coordinator for data correlation across generators.
 * Maintains in-memory state for active entities and ensures valid cross-references.
 * Reference data is loaded from PostgreSQL at startup.
 */
public class DataCorrelationManager {

    private static final Logger LOG = LoggerFactory.getLogger(DataCorrelationManager.class);

    // Singleton instance
    private static volatile DataCorrelationManager instance;

    // Reference data (loaded from PostgreSQL)
    private final List<String> warehouseIds;
    private final List<String> customerIds;
    private final List<String> vehicleIds;
    private final List<String> driverIds;
    private final List<String> productIds;
    private final List<String> routeIds;

    // Full reference data for lookups
    private final Map<String, Warehouse> warehouseMap;
    private final Map<String, Vehicle> vehicleMap;

    // Active state tracking
    private final ConcurrentHashMap<String, ShipmentState> activeShipments;
    private final ConcurrentHashMap<String, OrderState> activeOrders;
    private final ConcurrentHashMap<String, VehicleRuntimeState> activeVehicles;

    // Destination cities (derived from routes)
    private final List<DestinationInfo> destinations;

    // Warehouse locations for geo calculations
    private final Map<String, WarehouseLocation> warehouseLocations;

    private DataCorrelationManager(ReferenceData data) {
        // Extract IDs from loaded reference data
        this.warehouseIds = data.getWarehouses().stream()
            .map(Warehouse::getWarehouseId)
            .collect(Collectors.toList());

        this.customerIds = data.getCustomers().stream()
            .map(Customer::getCustomerId)
            .collect(Collectors.toList());

        this.vehicleIds = data.getVehicles().stream()
            .map(Vehicle::getVehicleId)
            .collect(Collectors.toList());

        this.driverIds = data.getDrivers().stream()
            .map(Driver::getDriverId)
            .collect(Collectors.toList());

        this.productIds = data.getProducts().stream()
            .map(Product::getProductId)
            .collect(Collectors.toList());

        this.routeIds = data.getRoutes().stream()
            .map(Route::getRouteId)
            .collect(Collectors.toList());

        // Build lookup maps
        this.warehouseMap = data.getWarehouses().stream()
            .collect(Collectors.toMap(Warehouse::getWarehouseId, w -> w));

        this.vehicleMap = data.getVehicles().stream()
            .collect(Collectors.toMap(Vehicle::getVehicleId, v -> v));

        // Build warehouse locations from loaded data
        this.warehouseLocations = buildWarehouseLocations(data.getWarehouses());

        // Build destinations from routes (unique city/country pairs)
        this.destinations = buildDestinations(data.getRoutes());

        // Initialize state tracking
        this.activeShipments = new ConcurrentHashMap<>();
        this.activeOrders = new ConcurrentHashMap<>();
        this.activeVehicles = new ConcurrentHashMap<>();

        LOG.info("DataCorrelationManager initialized from PostgreSQL with {} warehouses, {} customers, {} vehicles, {} drivers, {} products, {} routes",
            warehouseIds.size(), customerIds.size(), vehicleIds.size(), driverIds.size(), productIds.size(), routeIds.size());
    }

    /**
     * Initialize the DataCorrelationManager with reference data loaded from PostgreSQL.
     * Must be called once at startup before getInstance() is used.
     */
    public static synchronized void initialize(ReferenceData data) {
        if (instance != null) {
            throw new IllegalStateException("DataCorrelationManager already initialized");
        }
        instance = new DataCorrelationManager(data);
    }

    /**
     * Get the singleton instance. Must call initialize() first.
     */
    public static DataCorrelationManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("DataCorrelationManager not initialized. Call initialize() first.");
        }
        return instance;
    }

    // ========== Reference Data Accessors ==========

    public String getRandomWarehouseId() {
        return warehouseIds.get(ThreadLocalRandom.current().nextInt(warehouseIds.size()));
    }

    public String getRandomCustomerId() {
        return customerIds.get(ThreadLocalRandom.current().nextInt(customerIds.size()));
    }

    public String getRandomVehicleId() {
        return vehicleIds.get(ThreadLocalRandom.current().nextInt(vehicleIds.size()));
    }

    public String getRandomDriverId() {
        return driverIds.get(ThreadLocalRandom.current().nextInt(driverIds.size()));
    }

    public String getRandomProductId() {
        return productIds.get(ThreadLocalRandom.current().nextInt(productIds.size()));
    }

    public List<String> getWarehouseIds() {
        return Collections.unmodifiableList(warehouseIds);
    }

    public List<String> getVehicleIds() {
        return Collections.unmodifiableList(vehicleIds);
    }

    public List<String> getDriverIds() {
        return Collections.unmodifiableList(driverIds);
    }

    public Warehouse getWarehouse(String warehouseId) {
        return warehouseMap.get(warehouseId);
    }

    public Vehicle getVehicle(String vehicleId) {
        return vehicleMap.get(vehicleId);
    }

    public DestinationInfo getRandomDestination() {
        return destinations.get(ThreadLocalRandom.current().nextInt(destinations.size()));
    }

    public WarehouseLocation getWarehouseLocation(String warehouseId) {
        return warehouseLocations.get(warehouseId);
    }

    // ========== Shipment State Management ==========

    public void registerShipment(String shipmentId, String warehouseId, String customerId,
                                  String destinationCity, String destinationCountry,
                                  long expectedDelivery) {
        ShipmentState state = new ShipmentState(shipmentId, warehouseId, customerId,
                                                 destinationCity, destinationCountry, expectedDelivery);
        activeShipments.put(shipmentId, state);
        LOG.debug("Registered shipment: {} for customer: {}", shipmentId, customerId);
    }

    public ShipmentState getShipmentState(String shipmentId) {
        return activeShipments.get(shipmentId);
    }

    public void updateShipmentStatus(String shipmentId, String status) {
        ShipmentState state = activeShipments.get(shipmentId);
        if (state != null) {
            state.setCurrentStatus(status);
        }
    }

    public void removeShipment(String shipmentId) {
        activeShipments.remove(shipmentId);
        LOG.debug("Removed shipment: {}", shipmentId);
    }

    public List<String> getActiveShipmentIds() {
        return new ArrayList<>(activeShipments.keySet());
    }

    public List<String> getActiveShipmentIdsForWarehouse(String warehouseId) {
        return activeShipments.entrySet().stream()
            .filter(e -> warehouseId.equals(e.getValue().getWarehouseId()))
            .map(Map.Entry::getKey)
            .toList();
    }

    public List<String> getPickableShipmentIds() {
        return activeShipments.entrySet().stream()
            .filter(e -> "CREATED".equals(e.getValue().getCurrentStatus()))
            .map(Map.Entry::getKey)
            .toList();
    }

    public int getActiveShipmentCount() {
        return activeShipments.size();
    }

    // ========== Order State Management ==========

    public void registerOrder(String orderId, String customerId, List<String> shipmentIds) {
        OrderState state = new OrderState(orderId, customerId, shipmentIds);
        activeOrders.put(orderId, state);
        LOG.debug("Registered order: {} with {} shipments", orderId, shipmentIds.size());
    }

    public OrderState getOrderState(String orderId) {
        return activeOrders.get(orderId);
    }

    public void updateOrderStatus(String orderId, String status) {
        OrderState state = activeOrders.get(orderId);
        if (state != null) {
            state.setCurrentStatus(status);
        }
    }

    public void removeOrder(String orderId) {
        activeOrders.remove(orderId);
    }

    public int getActiveOrderCount() {
        return activeOrders.size();
    }

    // ========== Vehicle State Management ==========

    public void updateVehicleState(String vehicleId, String status, double latitude, double longitude) {
        VehicleRuntimeState state = activeVehicles.computeIfAbsent(vehicleId,
            k -> new VehicleRuntimeState(vehicleId));
        state.setStatus(status);
        state.setLatitude(latitude);
        state.setLongitude(longitude);
    }

    public VehicleRuntimeState getVehicleRuntimeState(String vehicleId) {
        return activeVehicles.get(vehicleId);
    }

    // ========== Helper Methods ==========

    private Map<String, WarehouseLocation> buildWarehouseLocations(List<Warehouse> warehouses) {
        Map<String, WarehouseLocation> locations = new HashMap<>();
        for (Warehouse w : warehouses) {
            locations.put(w.getWarehouseId(), new WarehouseLocation(
                w.getWarehouseId(),
                w.getCity(),
                w.getCountry(),
                w.getLatitude(),
                w.getLongitude()
            ));
        }
        return Collections.unmodifiableMap(locations);
    }

    private List<DestinationInfo> buildDestinations(List<Route> routes) {
        // Get unique destinations from routes
        Map<String, DestinationInfo> uniqueDestinations = new LinkedHashMap<>();
        for (Route r : routes) {
            String key = r.getDestinationCity() + "|" + r.getDestinationCountry();
            if (!uniqueDestinations.containsKey(key)) {
                // Note: Routes don't have lat/long for destinations, so we use placeholder values
                // In a real system, we'd have a cities table with coordinates
                uniqueDestinations.put(key, new DestinationInfo(
                    r.getDestinationCity(),
                    r.getDestinationCountry(),
                    0.0,  // Latitude not available in routes table
                    0.0   // Longitude not available in routes table
                ));
            }
        }
        LOG.info("Built {} unique destinations from routes", uniqueDestinations.size());
        return new ArrayList<>(uniqueDestinations.values());
    }

    // ========== Inner Classes ==========

    public static class ShipmentState {
        private final String shipmentId;
        private final String warehouseId;
        private final String customerId;
        private final String destinationCity;
        private final String destinationCountry;
        private final long expectedDelivery;
        private volatile String currentStatus;
        private final long createdAt;

        public ShipmentState(String shipmentId, String warehouseId, String customerId,
                             String destinationCity, String destinationCountry, long expectedDelivery) {
            this.shipmentId = shipmentId;
            this.warehouseId = warehouseId;
            this.customerId = customerId;
            this.destinationCity = destinationCity;
            this.destinationCountry = destinationCountry;
            this.expectedDelivery = expectedDelivery;
            this.currentStatus = "CREATED";
            this.createdAt = System.currentTimeMillis();
        }

        public String getShipmentId() { return shipmentId; }
        public String getWarehouseId() { return warehouseId; }
        public String getCustomerId() { return customerId; }
        public String getDestinationCity() { return destinationCity; }
        public String getDestinationCountry() { return destinationCountry; }
        public long getExpectedDelivery() { return expectedDelivery; }
        public String getCurrentStatus() { return currentStatus; }
        public void setCurrentStatus(String status) { this.currentStatus = status; }
        public long getCreatedAt() { return createdAt; }
    }

    public static class OrderState {
        private final String orderId;
        private final String customerId;
        private final List<String> shipmentIds;
        private volatile String currentStatus;
        private final long createdAt;

        public OrderState(String orderId, String customerId, List<String> shipmentIds) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.shipmentIds = new CopyOnWriteArrayList<>(shipmentIds);
            this.currentStatus = "RECEIVED";
            this.createdAt = System.currentTimeMillis();
        }

        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public List<String> getShipmentIds() { return Collections.unmodifiableList(shipmentIds); }
        public String getCurrentStatus() { return currentStatus; }
        public void setCurrentStatus(String status) { this.currentStatus = status; }
        public long getCreatedAt() { return createdAt; }
    }

    public static class VehicleRuntimeState {
        private final String vehicleId;
        private volatile String status;
        private volatile double latitude;
        private volatile double longitude;

        public VehicleRuntimeState(String vehicleId) {
            this.vehicleId = vehicleId;
            this.status = "IDLE";
        }

        public String getVehicleId() { return vehicleId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public double getLatitude() { return latitude; }
        public void setLatitude(double latitude) { this.latitude = latitude; }
        public double getLongitude() { return longitude; }
        public void setLongitude(double longitude) { this.longitude = longitude; }
    }

    public static class DestinationInfo {
        private final String city;
        private final String country;
        private final double latitude;
        private final double longitude;

        public DestinationInfo(String city, String country, double latitude, double longitude) {
            this.city = city;
            this.country = country;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public String getCity() { return city; }
        public String getCountry() { return country; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
    }

    public static class WarehouseLocation {
        private final String warehouseId;
        private final String city;
        private final String country;
        private final double latitude;
        private final double longitude;

        public WarehouseLocation(String warehouseId, String city, String country,
                                  double latitude, double longitude) {
            this.warehouseId = warehouseId;
            this.city = city;
            this.country = country;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public String getWarehouseId() { return warehouseId; }
        public String getCity() { return city; }
        public String getCountry() { return country; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
    }
}
