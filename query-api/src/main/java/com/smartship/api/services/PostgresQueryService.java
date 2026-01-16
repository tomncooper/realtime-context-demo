package com.smartship.api.services;

import com.smartship.api.model.reference.*;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Service for querying PostgreSQL reference data using Quarkus reactive client.
 * Provides non-blocking access to all 6 reference tables.
 */
@ApplicationScoped
public class PostgresQueryService {

    private static final Logger LOG = Logger.getLogger(PostgresQueryService.class);

    private final Pool client;

    public PostgresQueryService(Pool client) {
        this.client = client;
    }

    // ===========================================
    // Warehouse Queries
    // ===========================================

    /**
     * Get all warehouses.
     */
    public Uni<List<WarehouseDto>> getAllWarehouses() {
        LOG.debug("Querying all warehouses");
        return client.query("SELECT warehouse_id, name, city, country, latitude, longitude, status FROM warehouses ORDER BY warehouse_id")
            .execute()
            .onItem().transform(rowSet -> {
                List<WarehouseDto> warehouses = new ArrayList<>();
                for (Row row : rowSet) {
                    warehouses.add(mapRowToWarehouse(row));
                }
                LOG.debugf("Retrieved %d warehouses", warehouses.size());
                return warehouses;
            });
    }

    /**
     * Get warehouse by ID.
     */
    public Uni<WarehouseDto> getWarehouseById(String warehouseId) {
        LOG.debugf("Querying warehouse by ID: %s", warehouseId);
        return client.preparedQuery("SELECT warehouse_id, name, city, country, latitude, longitude, status FROM warehouses WHERE warehouse_id = $1")
            .execute(Tuple.of(warehouseId))
            .onItem().transform(rowSet -> {
                if (rowSet.size() == 0) {
                    return null;
                }
                return mapRowToWarehouse(rowSet.iterator().next());
            });
    }

    /**
     * Find warehouses by city (case-insensitive partial match).
     */
    public Uni<List<WarehouseDto>> findWarehousesByCity(String city) {
        LOG.debugf("Searching warehouses by city: %s", city);
        return client.preparedQuery("SELECT warehouse_id, name, city, country, latitude, longitude, status FROM warehouses WHERE LOWER(city) LIKE LOWER($1) ORDER BY warehouse_id")
            .execute(Tuple.of("%" + city + "%"))
            .onItem().transform(rowSet -> {
                List<WarehouseDto> warehouses = new ArrayList<>();
                for (Row row : rowSet) {
                    warehouses.add(mapRowToWarehouse(row));
                }
                return warehouses;
            });
    }

    private WarehouseDto mapRowToWarehouse(Row row) {
        return new WarehouseDto(
            row.getString("warehouse_id"),
            row.getString("name"),
            row.getString("city"),
            row.getString("country"),
            row.getDouble("latitude"),
            row.getDouble("longitude"),
            row.getString("status")
        );
    }

    // ===========================================
    // Customer Queries
    // ===========================================

    /**
     * Get customers with pagination.
     */
    public Uni<List<CustomerDto>> getCustomers(int limit, int offset) {
        LOG.debugf("Querying customers with limit=%d, offset=%d", limit, offset);
        return client.preparedQuery("SELECT customer_id, company_name, contact_email, sla_tier, account_status FROM customers ORDER BY customer_id LIMIT $1 OFFSET $2")
            .execute(Tuple.of(limit, offset))
            .onItem().transform(rowSet -> {
                List<CustomerDto> customers = new ArrayList<>();
                for (Row row : rowSet) {
                    customers.add(mapRowToCustomer(row));
                }
                LOG.debugf("Retrieved %d customers", customers.size());
                return customers;
            });
    }

    /**
     * Get customer by ID.
     */
    public Uni<CustomerDto> getCustomerById(String customerId) {
        LOG.debugf("Querying customer by ID: %s", customerId);
        return client.preparedQuery("SELECT customer_id, company_name, contact_email, sla_tier, account_status FROM customers WHERE customer_id = $1")
            .execute(Tuple.of(customerId))
            .onItem().transform(rowSet -> {
                if (rowSet.size() == 0) {
                    return null;
                }
                return mapRowToCustomer(rowSet.iterator().next());
            });
    }

    /**
     * Find customers by company name (case-insensitive partial match).
     */
    public Uni<List<CustomerDto>> findCustomersByCompanyName(String companyName) {
        LOG.debugf("Searching customers by company name: %s", companyName);
        return client.preparedQuery("SELECT customer_id, company_name, contact_email, sla_tier, account_status FROM customers WHERE LOWER(company_name) LIKE LOWER($1) ORDER BY customer_id")
            .execute(Tuple.of("%" + companyName + "%"))
            .onItem().transform(rowSet -> {
                List<CustomerDto> customers = new ArrayList<>();
                for (Row row : rowSet) {
                    customers.add(mapRowToCustomer(row));
                }
                return customers;
            });
    }

    /**
     * Find customers by SLA tier.
     */
    public Uni<List<CustomerDto>> findCustomersBySLATier(String slaTier) {
        LOG.debugf("Querying customers by SLA tier: %s", slaTier);
        return client.preparedQuery("SELECT customer_id, company_name, contact_email, sla_tier, account_status FROM customers WHERE sla_tier = $1 ORDER BY customer_id")
            .execute(Tuple.of(slaTier.toUpperCase()))
            .onItem().transform(rowSet -> {
                List<CustomerDto> customers = new ArrayList<>();
                for (Row row : rowSet) {
                    customers.add(mapRowToCustomer(row));
                }
                return customers;
            });
    }

    private CustomerDto mapRowToCustomer(Row row) {
        return new CustomerDto(
            row.getString("customer_id"),
            row.getString("company_name"),
            row.getString("contact_email"),
            row.getString("sla_tier"),
            row.getString("account_status")
        );
    }

    // ===========================================
    // Vehicle Queries
    // ===========================================

    /**
     * Get all vehicles.
     */
    public Uni<List<VehicleRefDto>> getAllVehicles() {
        LOG.debug("Querying all vehicles");
        return client.query("SELECT vehicle_id, vehicle_type, license_plate, capacity_kg, capacity_cubic_m, home_warehouse_id, status, fuel_type FROM vehicles ORDER BY vehicle_id")
            .execute()
            .onItem().transform(rowSet -> {
                List<VehicleRefDto> vehicles = new ArrayList<>();
                for (Row row : rowSet) {
                    vehicles.add(mapRowToVehicle(row));
                }
                LOG.debugf("Retrieved %d vehicles", vehicles.size());
                return vehicles;
            });
    }

    /**
     * Get vehicle by ID.
     */
    public Uni<VehicleRefDto> getVehicleById(String vehicleId) {
        LOG.debugf("Querying vehicle by ID: %s", vehicleId);
        return client.preparedQuery("SELECT vehicle_id, vehicle_type, license_plate, capacity_kg, capacity_cubic_m, home_warehouse_id, status, fuel_type FROM vehicles WHERE vehicle_id = $1")
            .execute(Tuple.of(vehicleId))
            .onItem().transform(rowSet -> {
                if (rowSet.size() == 0) {
                    return null;
                }
                return mapRowToVehicle(rowSet.iterator().next());
            });
    }

    /**
     * Find vehicles by home warehouse.
     */
    public Uni<List<VehicleRefDto>> findVehiclesByHomeWarehouse(String warehouseId) {
        LOG.debugf("Querying vehicles by home warehouse: %s", warehouseId);
        return client.preparedQuery("SELECT vehicle_id, vehicle_type, license_plate, capacity_kg, capacity_cubic_m, home_warehouse_id, status, fuel_type FROM vehicles WHERE home_warehouse_id = $1 ORDER BY vehicle_id")
            .execute(Tuple.of(warehouseId))
            .onItem().transform(rowSet -> {
                List<VehicleRefDto> vehicles = new ArrayList<>();
                for (Row row : rowSet) {
                    vehicles.add(mapRowToVehicle(row));
                }
                return vehicles;
            });
    }

    private VehicleRefDto mapRowToVehicle(Row row) {
        return new VehicleRefDto(
            row.getString("vehicle_id"),
            row.getString("vehicle_type"),
            row.getString("license_plate"),
            row.getDouble("capacity_kg"),
            row.getDouble("capacity_cubic_m"),
            row.getString("home_warehouse_id"),
            row.getString("status"),
            row.getString("fuel_type")
        );
    }

    // ===========================================
    // Product Queries
    // ===========================================

    /**
     * Get products by category with pagination.
     */
    public Uni<List<ProductDto>> getProductsByCategory(String category, int limit, int offset) {
        LOG.debugf("Querying products by category: %s (limit=%d, offset=%d)", category, limit, offset);
        return client.preparedQuery("SELECT product_id, sku, name, category, weight_kg, length_cm, width_cm, height_cm, unit_price FROM products WHERE category = $1 ORDER BY product_id LIMIT $2 OFFSET $3")
            .execute(Tuple.of(category.toUpperCase(), limit, offset))
            .onItem().transform(rowSet -> {
                List<ProductDto> products = new ArrayList<>();
                for (Row row : rowSet) {
                    products.add(mapRowToProduct(row));
                }
                return products;
            });
    }

    /**
     * Search products by name or SKU.
     */
    public Uni<List<ProductDto>> searchProducts(String searchTerm, int limit) {
        LOG.debugf("Searching products by term: %s (limit=%d)", searchTerm, limit);
        return client.preparedQuery("SELECT product_id, sku, name, category, weight_kg, length_cm, width_cm, height_cm, unit_price FROM products WHERE LOWER(name) LIKE LOWER($1) OR LOWER(sku) LIKE LOWER($1) ORDER BY product_id LIMIT $2")
            .execute(Tuple.of("%" + searchTerm + "%", limit))
            .onItem().transform(rowSet -> {
                List<ProductDto> products = new ArrayList<>();
                for (Row row : rowSet) {
                    products.add(mapRowToProduct(row));
                }
                return products;
            });
    }

    private ProductDto mapRowToProduct(Row row) {
        return new ProductDto(
            row.getString("product_id"),
            row.getString("sku"),
            row.getString("name"),
            row.getString("category"),
            row.getDouble("weight_kg"),
            row.getDouble("length_cm"),
            row.getDouble("width_cm"),
            row.getDouble("height_cm"),
            row.getDouble("unit_price")
        );
    }

    // ===========================================
    // Driver Queries
    // ===========================================

    /**
     * Get all drivers.
     */
    public Uni<List<DriverDto>> getAllDrivers() {
        LOG.debug("Querying all drivers");
        return client.query("SELECT driver_id, first_name, last_name, license_type, certifications, assigned_vehicle_id, home_warehouse_id, status FROM drivers ORDER BY driver_id")
            .execute()
            .onItem().transform(rowSet -> {
                List<DriverDto> drivers = new ArrayList<>();
                for (Row row : rowSet) {
                    drivers.add(mapRowToDriver(row));
                }
                LOG.debugf("Retrieved %d drivers", drivers.size());
                return drivers;
            });
    }

    /**
     * Get driver by ID.
     */
    public Uni<DriverDto> getDriverById(String driverId) {
        LOG.debugf("Querying driver by ID: %s", driverId);
        return client.preparedQuery("SELECT driver_id, first_name, last_name, license_type, certifications, assigned_vehicle_id, home_warehouse_id, status FROM drivers WHERE driver_id = $1")
            .execute(Tuple.of(driverId))
            .onItem().transform(rowSet -> {
                if (rowSet.size() == 0) {
                    return null;
                }
                return mapRowToDriver(rowSet.iterator().next());
            });
    }

    /**
     * Find drivers by home warehouse.
     */
    public Uni<List<DriverDto>> findDriversByHomeWarehouse(String warehouseId) {
        LOG.debugf("Querying drivers by home warehouse: %s", warehouseId);
        return client.preparedQuery("SELECT driver_id, first_name, last_name, license_type, certifications, assigned_vehicle_id, home_warehouse_id, status FROM drivers WHERE home_warehouse_id = $1 ORDER BY driver_id")
            .execute(Tuple.of(warehouseId))
            .onItem().transform(rowSet -> {
                List<DriverDto> drivers = new ArrayList<>();
                for (Row row : rowSet) {
                    drivers.add(mapRowToDriver(row));
                }
                return drivers;
            });
    }

    /**
     * Find available drivers.
     */
    public Uni<List<DriverDto>> findAvailableDrivers() {
        LOG.debug("Querying available drivers");
        return client.query("SELECT driver_id, first_name, last_name, license_type, certifications, assigned_vehicle_id, home_warehouse_id, status FROM drivers WHERE status = 'AVAILABLE' ORDER BY driver_id")
            .execute()
            .onItem().transform(rowSet -> {
                List<DriverDto> drivers = new ArrayList<>();
                for (Row row : rowSet) {
                    drivers.add(mapRowToDriver(row));
                }
                return drivers;
            });
    }

    private DriverDto mapRowToDriver(Row row) {
        // Handle PostgreSQL text array
        String[] certArray = row.getArrayOfStrings("certifications");
        List<String> certifications = certArray != null ? Arrays.asList(certArray) : List.of();

        return new DriverDto(
            row.getString("driver_id"),
            row.getString("first_name"),
            row.getString("last_name"),
            row.getString("license_type"),
            certifications,
            row.getString("assigned_vehicle_id"),
            row.getString("home_warehouse_id"),
            row.getString("status")
        );
    }

    // ===========================================
    // Route Queries
    // ===========================================

    /**
     * Get all routes.
     */
    public Uni<List<RouteDto>> getAllRoutes() {
        LOG.debug("Querying all routes");
        return client.query("SELECT route_id, origin_warehouse_id, destination_city, destination_country, distance_km, estimated_hours, route_type, active FROM routes ORDER BY route_id")
            .execute()
            .onItem().transform(rowSet -> {
                List<RouteDto> routes = new ArrayList<>();
                for (Row row : rowSet) {
                    routes.add(mapRowToRoute(row));
                }
                LOG.debugf("Retrieved %d routes", routes.size());
                return routes;
            });
    }

    /**
     * Find routes by origin warehouse.
     */
    public Uni<List<RouteDto>> findRoutesByOrigin(String warehouseId) {
        LOG.debugf("Querying routes by origin warehouse: %s", warehouseId);
        return client.preparedQuery("SELECT route_id, origin_warehouse_id, destination_city, destination_country, distance_km, estimated_hours, route_type, active FROM routes WHERE origin_warehouse_id = $1 ORDER BY route_id")
            .execute(Tuple.of(warehouseId))
            .onItem().transform(rowSet -> {
                List<RouteDto> routes = new ArrayList<>();
                for (Row row : rowSet) {
                    routes.add(mapRowToRoute(row));
                }
                return routes;
            });
    }

    /**
     * Find routes by destination city.
     */
    public Uni<List<RouteDto>> findRoutesByDestination(String city) {
        LOG.debugf("Querying routes by destination city: %s", city);
        return client.preparedQuery("SELECT route_id, origin_warehouse_id, destination_city, destination_country, distance_km, estimated_hours, route_type, active FROM routes WHERE LOWER(destination_city) LIKE LOWER($1) ORDER BY route_id")
            .execute(Tuple.of("%" + city + "%"))
            .onItem().transform(rowSet -> {
                List<RouteDto> routes = new ArrayList<>();
                for (Row row : rowSet) {
                    routes.add(mapRowToRoute(row));
                }
                return routes;
            });
    }

    private RouteDto mapRowToRoute(Row row) {
        return new RouteDto(
            row.getString("route_id"),
            row.getString("origin_warehouse_id"),
            row.getString("destination_city"),
            row.getString("destination_country"),
            row.getDouble("distance_km"),
            row.getDouble("estimated_hours"),
            row.getString("route_type"),
            row.getBoolean("active")
        );
    }
}
