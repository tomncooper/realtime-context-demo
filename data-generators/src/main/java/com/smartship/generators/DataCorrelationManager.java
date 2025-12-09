package com.smartship.generators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Central coordinator for data correlation across generators.
 * Maintains in-memory state for active entities and ensures valid cross-references.
 */
public class DataCorrelationManager {

    private static final Logger LOG = LoggerFactory.getLogger(DataCorrelationManager.class);

    // Singleton instance
    private static volatile DataCorrelationManager instance;

    // Reference data (loaded from PostgreSQL seed data patterns)
    private final List<String> warehouseIds;
    private final List<String> customerIds;
    private final List<String> vehicleIds;
    private final List<String> driverIds;
    private final List<String> productIds;
    private final List<String> routeIds;

    // Active state tracking
    private final ConcurrentHashMap<String, ShipmentState> activeShipments;
    private final ConcurrentHashMap<String, OrderState> activeOrders;
    private final ConcurrentHashMap<String, VehicleState> activeVehicles;

    // Destination cities (from routes table)
    private final List<DestinationInfo> destinations;

    // Warehouse locations for geo calculations
    private final Map<String, WarehouseLocation> warehouseLocations;

    private DataCorrelationManager() {
        // Initialize reference data (matching PostgreSQL seed data)
        this.warehouseIds = List.of("WH-RTM", "WH-FRA", "WH-BCN", "WH-WAW", "WH-STO");
        this.customerIds = generateSequentialIds("CUST-", 200, 4);
        this.vehicleIds = generateSequentialIds("VEH-", 50, 3);
        this.driverIds = generateSequentialIds("DRV-", 75, 3);
        this.productIds = generateSequentialIds("PROD-", 10000, 6);
        this.routeIds = generateSequentialIds("RTE-", 100, 3);

        this.activeShipments = new ConcurrentHashMap<>();
        this.activeOrders = new ConcurrentHashMap<>();
        this.activeVehicles = new ConcurrentHashMap<>();

        this.destinations = initializeDestinations();
        this.warehouseLocations = initializeWarehouseLocations();

        LOG.info("DataCorrelationManager initialized with {} warehouses, {} customers, {} vehicles, {} products",
            warehouseIds.size(), customerIds.size(), vehicleIds.size(), productIds.size());
    }

    public static DataCorrelationManager getInstance() {
        if (instance == null) {
            synchronized (DataCorrelationManager.class) {
                if (instance == null) {
                    instance = new DataCorrelationManager();
                }
            }
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
        VehicleState state = activeVehicles.computeIfAbsent(vehicleId,
            k -> new VehicleState(vehicleId));
        state.setStatus(status);
        state.setLatitude(latitude);
        state.setLongitude(longitude);
    }

    public VehicleState getVehicleState(String vehicleId) {
        return activeVehicles.get(vehicleId);
    }

    // ========== Helper Methods ==========

    private List<String> generateSequentialIds(String prefix, int count, int padding) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            ids.add(prefix + String.format("%0" + padding + "d", i));
        }
        return ids;
    }

    private Map<String, WarehouseLocation> initializeWarehouseLocations() {
        Map<String, WarehouseLocation> locations = new HashMap<>();
        locations.put("WH-RTM", new WarehouseLocation("WH-RTM", "Rotterdam", "Netherlands", 51.9225, 4.47917));
        locations.put("WH-FRA", new WarehouseLocation("WH-FRA", "Frankfurt", "Germany", 50.1109, 8.68213));
        locations.put("WH-BCN", new WarehouseLocation("WH-BCN", "Barcelona", "Spain", 41.3874, 2.16996));
        locations.put("WH-WAW", new WarehouseLocation("WH-WAW", "Warsaw", "Poland", 52.2297, 21.0122));
        locations.put("WH-STO", new WarehouseLocation("WH-STO", "Stockholm", "Sweden", 59.3293, 18.0686));
        return Collections.unmodifiableMap(locations);
    }

    private List<DestinationInfo> initializeDestinations() {
        // Major European cities (matching routes table)
        return List.of(
            new DestinationInfo("Amsterdam", "Netherlands", 52.3676, 4.9041),
            new DestinationInfo("Berlin", "Germany", 52.5200, 13.4050),
            new DestinationInfo("Paris", "France", 48.8566, 2.3522),
            new DestinationInfo("Madrid", "Spain", 40.4168, -3.7038),
            new DestinationInfo("Rome", "Italy", 41.9028, 12.4964),
            new DestinationInfo("Vienna", "Austria", 48.2082, 16.3738),
            new DestinationInfo("Prague", "Czech Republic", 50.0755, 14.4378),
            new DestinationInfo("Budapest", "Hungary", 47.4979, 19.0402),
            new DestinationInfo("Copenhagen", "Denmark", 55.6761, 12.5683),
            new DestinationInfo("Oslo", "Norway", 59.9139, 10.7522),
            new DestinationInfo("Helsinki", "Finland", 60.1699, 24.9384),
            new DestinationInfo("Brussels", "Belgium", 50.8503, 4.3517),
            new DestinationInfo("Zurich", "Switzerland", 47.3769, 8.5417),
            new DestinationInfo("Milan", "Italy", 45.4642, 9.1900),
            new DestinationInfo("Munich", "Germany", 48.1351, 11.5820),
            new DestinationInfo("Hamburg", "Germany", 53.5511, 9.9937),
            new DestinationInfo("Lyon", "France", 45.7640, 4.8357),
            new DestinationInfo("Lisbon", "Portugal", 38.7223, -9.1393),
            new DestinationInfo("Dublin", "Ireland", 53.3498, -6.2603),
            new DestinationInfo("London", "United Kingdom", 51.5074, -0.1278)
        );
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

    public static class VehicleState {
        private final String vehicleId;
        private volatile String status;
        private volatile double latitude;
        private volatile double longitude;

        public VehicleState(String vehicleId) {
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
