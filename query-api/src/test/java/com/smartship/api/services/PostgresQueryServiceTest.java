package com.smartship.api.services;

import com.smartship.api.model.reference.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PostgresQueryService using Quarkus DevServices.
 * Tests run against a PostgreSQL testcontainer with test-init.sql data.
 */
@QuarkusTest
class PostgresQueryServiceTest {

    @Inject
    PostgresQueryService service;

    // ==========================================
    // Warehouse Tests
    // ==========================================

    @Test
    void testGetAllWarehouses() {
        List<WarehouseDto> warehouses = service.getAllWarehouses()
            .await().indefinitely();

        assertNotNull(warehouses);
        assertEquals(3, warehouses.size());

        // Verify ordering by warehouse_id
        assertEquals("WH-BCN", warehouses.get(0).warehouseId());
        assertEquals("WH-FRA", warehouses.get(1).warehouseId());
        assertEquals("WH-RTM", warehouses.get(2).warehouseId());
    }

    @Test
    void testGetWarehouseById_Found() {
        WarehouseDto warehouse = service.getWarehouseById("WH-RTM")
            .await().indefinitely();

        assertNotNull(warehouse);
        assertEquals("WH-RTM", warehouse.warehouseId());
        assertEquals("Rotterdam Distribution Center", warehouse.name());
        assertEquals("Rotterdam", warehouse.city());
        assertEquals("Netherlands", warehouse.country());
        assertEquals("OPERATIONAL", warehouse.status());
    }

    @Test
    void testGetWarehouseById_NotFound() {
        WarehouseDto warehouse = service.getWarehouseById("WH-NONEXISTENT")
            .await().indefinitely();

        assertNull(warehouse);
    }

    @Test
    void testFindWarehousesByCity() {
        List<WarehouseDto> warehouses = service.findWarehousesByCity("Rotterdam")
            .await().indefinitely();

        assertNotNull(warehouses);
        assertEquals(1, warehouses.size());
        assertEquals("WH-RTM", warehouses.get(0).warehouseId());
    }

    @Test
    void testFindWarehousesByCity_PartialMatch() {
        List<WarehouseDto> warehouses = service.findWarehousesByCity("frank")
            .await().indefinitely();

        assertNotNull(warehouses);
        assertEquals(1, warehouses.size());
        assertEquals("WH-FRA", warehouses.get(0).warehouseId());
    }

    // ==========================================
    // Customer Tests
    // ==========================================

    @Test
    void testGetCustomers_WithPagination() {
        List<CustomerDto> customers = service.getCustomers(3, 0)
            .await().indefinitely();

        assertNotNull(customers);
        assertEquals(3, customers.size());
        assertEquals("CUST-0001", customers.get(0).customerId());
    }

    @Test
    void testGetCustomers_WithOffset() {
        List<CustomerDto> customers = service.getCustomers(2, 2)
            .await().indefinitely();

        assertNotNull(customers);
        assertEquals(2, customers.size());
        assertEquals("CUST-0003", customers.get(0).customerId());
    }

    @Test
    void testGetCustomerById_Found() {
        CustomerDto customer = service.getCustomerById("CUST-0001")
            .await().indefinitely();

        assertNotNull(customer);
        assertEquals("CUST-0001", customer.customerId());
        assertEquals("Test Company One", customer.companyName());
        assertEquals("test1@example.com", customer.contactEmail());
        assertEquals("STANDARD", customer.slaTier());
        assertEquals("ACTIVE", customer.accountStatus());
    }

    @Test
    void testGetCustomerById_NotFound() {
        CustomerDto customer = service.getCustomerById("CUST-9999")
            .await().indefinitely();

        assertNull(customer);
    }

    @Test
    void testFindCustomersByCompanyName() {
        List<CustomerDto> customers = service.findCustomersByCompanyName("Test Company")
            .await().indefinitely();

        assertNotNull(customers);
        assertEquals(4, customers.size()); // Test Company One, Two, Three, Four
    }

    @Test
    void testFindCustomersBySLATier() {
        List<CustomerDto> customers = service.findCustomersBySLATier("EXPRESS")
            .await().indefinitely();

        assertNotNull(customers);
        assertEquals(1, customers.size());
        assertEquals("CUST-0002", customers.get(0).customerId());
    }

    // ==========================================
    // Vehicle Tests
    // ==========================================

    @Test
    void testGetAllVehicles() {
        List<VehicleRefDto> vehicles = service.getAllVehicles()
            .await().indefinitely();

        assertNotNull(vehicles);
        assertEquals(3, vehicles.size());
    }

    @Test
    void testGetVehicleById_Found() {
        VehicleRefDto vehicle = service.getVehicleById("VEH-001")
            .await().indefinitely();

        assertNotNull(vehicle);
        assertEquals("VEH-001", vehicle.vehicleId());
        assertEquals("VAN", vehicle.vehicleType());
        assertEquals("TEST-VAN-001", vehicle.licensePlate());
        assertEquals(1500.0, vehicle.capacityKg());
        assertEquals("WH-RTM", vehicle.homeWarehouseId());
    }

    @Test
    void testGetVehicleById_NotFound() {
        VehicleRefDto vehicle = service.getVehicleById("VEH-999")
            .await().indefinitely();

        assertNull(vehicle);
    }

    @Test
    void testFindVehiclesByHomeWarehouse() {
        List<VehicleRefDto> vehicles = service.findVehiclesByHomeWarehouse("WH-RTM")
            .await().indefinitely();

        assertNotNull(vehicles);
        assertEquals(1, vehicles.size());
        assertEquals("VEH-001", vehicles.get(0).vehicleId());
    }

    // ==========================================
    // Driver Tests
    // ==========================================

    @Test
    void testGetAllDrivers() {
        List<DriverDto> drivers = service.getAllDrivers()
            .await().indefinitely();

        assertNotNull(drivers);
        assertEquals(3, drivers.size());
    }

    @Test
    void testGetDriverById_Found() {
        DriverDto driver = service.getDriverById("DRV-001")
            .await().indefinitely();

        assertNotNull(driver);
        assertEquals("DRV-001", driver.driverId());
        assertEquals("Jan", driver.firstName());
        assertEquals("van der Berg", driver.lastName());
        assertEquals("C", driver.licenseType());
        assertTrue(driver.certifications().contains("ADR"));
        assertTrue(driver.certifications().contains("FORKLIFT"));
        assertEquals("VEH-001", driver.assignedVehicleId());
        assertEquals("WH-RTM", driver.homeWarehouseId());
        assertEquals("AVAILABLE", driver.status());
    }

    @Test
    void testGetDriverById_NotFound() {
        DriverDto driver = service.getDriverById("DRV-999")
            .await().indefinitely();

        assertNull(driver);
    }

    @Test
    void testFindDriversByHomeWarehouse() {
        List<DriverDto> drivers = service.findDriversByHomeWarehouse("WH-FRA")
            .await().indefinitely();

        assertNotNull(drivers);
        assertEquals(1, drivers.size());
        assertEquals("DRV-002", drivers.get(0).driverId());
    }

    @Test
    void testFindAvailableDrivers() {
        List<DriverDto> drivers = service.findAvailableDrivers()
            .await().indefinitely();

        assertNotNull(drivers);
        assertEquals(2, drivers.size()); // DRV-001 and DRV-003 are AVAILABLE
    }

    // ==========================================
    // Route Tests
    // ==========================================

    @Test
    void testGetAllRoutes() {
        List<RouteDto> routes = service.getAllRoutes()
            .await().indefinitely();

        assertNotNull(routes);
        assertEquals(3, routes.size());
    }

    @Test
    void testFindRoutesByOrigin() {
        List<RouteDto> routes = service.findRoutesByOrigin("WH-RTM")
            .await().indefinitely();

        assertNotNull(routes);
        assertEquals(1, routes.size());
        assertEquals("RTE-001", routes.get(0).routeId());
        assertEquals("Amsterdam", routes.get(0).destinationCity());
    }

    @Test
    void testFindRoutesByDestination() {
        List<RouteDto> routes = service.findRoutesByDestination("Munich")
            .await().indefinitely();

        assertNotNull(routes);
        assertEquals(1, routes.size());
        assertEquals("RTE-002", routes.get(0).routeId());
    }

    // ==========================================
    // Product Tests
    // ==========================================

    @Test
    void testGetProductsByCategory() {
        List<ProductDto> products = service.getProductsByCategory("ELECTRONICS", 10, 0)
            .await().indefinitely();

        assertNotNull(products);
        assertEquals(1, products.size());
        assertEquals("PRD-00001", products.get(0).productId());
        assertEquals("Test Electronics Item", products.get(0).name());
    }

    @Test
    void testSearchProducts() {
        List<ProductDto> products = service.searchProducts("Test", 10)
            .await().indefinitely();

        assertNotNull(products);
        assertEquals(5, products.size());
    }

    @Test
    void testSearchProducts_BySKU() {
        List<ProductDto> products = service.searchProducts("SKU-TEST-001", 10)
            .await().indefinitely();

        assertNotNull(products);
        assertEquals(1, products.size());
        assertEquals("PRD-00001", products.get(0).productId());
    }
}
