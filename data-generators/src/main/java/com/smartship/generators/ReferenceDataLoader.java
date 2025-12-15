package com.smartship.generators;

import com.smartship.generators.model.Customer;
import com.smartship.generators.model.Driver;
import com.smartship.generators.model.Product;
import com.smartship.generators.model.ReferenceData;
import com.smartship.generators.model.Route;
import com.smartship.generators.model.Vehicle;
import com.smartship.generators.model.Warehouse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Loads reference data from PostgreSQL at startup.
 * Implements retry logic to wait for database availability.
 */
public class ReferenceDataLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ReferenceDataLoader.class);

    private static final int MAX_RETRIES = 30;
    private static final long INITIAL_DELAY_MS = 1000;
    private static final long MAX_DELAY_MS = 30000;

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public ReferenceDataLoader() {
        String host = getEnvOrDefault("POSTGRES_HOST", "localhost");
        String port = getEnvOrDefault("POSTGRES_PORT", "5432");
        String database = getEnvOrDefault("POSTGRES_DB", "smartship");
        this.username = getEnvOrDefault("POSTGRES_USER", "smartship");
        this.password = getEnvOrDefault("POSTGRES_PASSWORD", "smartship123");
        this.jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, database);

        LOG.info("ReferenceDataLoader configured with JDBC URL: {}", jdbcUrl);
    }

    /**
     * Load all reference data from PostgreSQL with retry logic.
     */
    public ReferenceData loadAllReferenceData() {
        Connection connection = connectWithRetry();
        try {
            LOG.info("Loading reference data from PostgreSQL...");

            List<Warehouse> warehouses = loadWarehouses(connection);
            List<Customer> customers = loadCustomers(connection);
            List<Vehicle> vehicles = loadVehicles(connection);
            List<Driver> drivers = loadDrivers(connection);
            List<Product> products = loadProducts(connection);
            List<Route> routes = loadRoutes(connection);

            ReferenceData data = new ReferenceData(warehouses, customers, vehicles, drivers, products, routes);
            LOG.info("Reference data loaded successfully: {}", data);
            return data;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to load reference data", e);
        } finally {
            closeQuietly(connection);
        }
    }

    private Connection connectWithRetry() {
        int attempt = 0;
        long delay = INITIAL_DELAY_MS;

        while (attempt < MAX_RETRIES) {
            try {
                LOG.info("Attempting to connect to PostgreSQL (attempt {}/{})", attempt + 1, MAX_RETRIES);
                Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
                LOG.info("Successfully connected to PostgreSQL");
                return conn;
            } catch (SQLException e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    throw new RuntimeException("Failed to connect to PostgreSQL after " + MAX_RETRIES + " attempts", e);
                }
                LOG.warn("Failed to connect to PostgreSQL: {}. Retrying in {} ms...", e.getMessage(), delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting to retry", ie);
                }
                delay = Math.min(delay * 2, MAX_DELAY_MS);
            }
        }
        throw new RuntimeException("Failed to connect to PostgreSQL");
    }

    private List<Warehouse> loadWarehouses(Connection conn) throws SQLException {
        List<Warehouse> warehouses = new ArrayList<>();
        String sql = "SELECT warehouse_id, name, city, country, latitude, longitude, status FROM warehouses";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                warehouses.add(new Warehouse(
                    rs.getString("warehouse_id"),
                    rs.getString("name"),
                    rs.getString("city"),
                    rs.getString("country"),
                    rs.getDouble("latitude"),
                    rs.getDouble("longitude"),
                    rs.getString("status")
                ));
            }
        }
        LOG.info("Loaded {} warehouses", warehouses.size());
        return warehouses;
    }

    private List<Customer> loadCustomers(Connection conn) throws SQLException {
        List<Customer> customers = new ArrayList<>();
        String sql = "SELECT customer_id, company_name, contact_email, sla_tier, account_status FROM customers";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                customers.add(new Customer(
                    rs.getString("customer_id"),
                    rs.getString("company_name"),
                    rs.getString("contact_email"),
                    rs.getString("sla_tier"),
                    rs.getString("account_status")
                ));
            }
        }
        LOG.info("Loaded {} customers", customers.size());
        return customers;
    }

    private List<Vehicle> loadVehicles(Connection conn) throws SQLException {
        List<Vehicle> vehicles = new ArrayList<>();
        String sql = "SELECT vehicle_id, vehicle_type, license_plate, capacity_kg, capacity_cubic_m, " +
                     "home_warehouse_id, status, fuel_type FROM vehicles";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                vehicles.add(new Vehicle(
                    rs.getString("vehicle_id"),
                    rs.getString("vehicle_type"),
                    rs.getString("license_plate"),
                    rs.getDouble("capacity_kg"),
                    rs.getDouble("capacity_cubic_m"),
                    rs.getString("home_warehouse_id"),
                    rs.getString("status"),
                    rs.getString("fuel_type")
                ));
            }
        }
        LOG.info("Loaded {} vehicles", vehicles.size());
        return vehicles;
    }

    private List<Driver> loadDrivers(Connection conn) throws SQLException {
        List<Driver> drivers = new ArrayList<>();
        String sql = "SELECT driver_id, first_name, last_name, license_type, certifications, " +
                     "assigned_vehicle_id, home_warehouse_id, status FROM drivers";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                // Parse PostgreSQL array to List<String>
                Array certArray = rs.getArray("certifications");
                List<String> certifications = certArray != null
                    ? Arrays.asList((String[]) certArray.getArray())
                    : Collections.emptyList();

                drivers.add(new Driver(
                    rs.getString("driver_id"),
                    rs.getString("first_name"),
                    rs.getString("last_name"),
                    rs.getString("license_type"),
                    certifications,
                    rs.getString("assigned_vehicle_id"),
                    rs.getString("home_warehouse_id"),
                    rs.getString("status")
                ));
            }
        }
        LOG.info("Loaded {} drivers", drivers.size());
        return drivers;
    }

    private List<Product> loadProducts(Connection conn) throws SQLException {
        List<Product> products = new ArrayList<>();
        String sql = "SELECT product_id, sku, name, category, weight_kg, length_cm, width_cm, height_cm, unit_price FROM products";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                products.add(new Product(
                    rs.getString("product_id"),
                    rs.getString("sku"),
                    rs.getString("name"),
                    rs.getString("category"),
                    rs.getDouble("weight_kg"),
                    rs.getDouble("length_cm"),
                    rs.getDouble("width_cm"),
                    rs.getDouble("height_cm"),
                    rs.getDouble("unit_price")
                ));
            }
        }
        LOG.info("Loaded {} products", products.size());
        return products;
    }

    private List<Route> loadRoutes(Connection conn) throws SQLException {
        List<Route> routes = new ArrayList<>();
        String sql = "SELECT route_id, origin_warehouse_id, destination_city, destination_country, " +
                     "distance_km, estimated_hours, route_type, active FROM routes";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                routes.add(new Route(
                    rs.getString("route_id"),
                    rs.getString("origin_warehouse_id"),
                    rs.getString("destination_city"),
                    rs.getString("destination_country"),
                    rs.getDouble("distance_km"),
                    rs.getDouble("estimated_hours"),
                    rs.getString("route_type"),
                    rs.getBoolean("active")
                ));
            }
        }
        LOG.info("Loaded {} routes", routes.size());
        return routes;
    }

    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                LOG.warn("Error closing connection", e);
            }
        }
    }

    private String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null ? value : defaultValue;
    }
}
