package com.smartship.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for ReferenceDataResource REST endpoints.
 * Uses PostgreSQL DevServices with test-init.sql data.
 */
@QuarkusTest
class ReferenceDataResourceTest {

    // ==========================================
    // Warehouse Endpoints
    // ==========================================

    @Test
    void testGetAllWarehouses() {
        given()
            .when().get("/api/reference/warehouses")
            .then()
            .statusCode(200)
            .body("warehouses", hasSize(3))
            .body("source", equalTo("postgresql"))
            .body("count", equalTo(3));
    }

    @Test
    void testGetWarehouseById_Found() {
        given()
            .when().get("/api/reference/warehouses/WH-RTM")
            .then()
            .statusCode(200)
            .body("warehouse.warehouse_id", equalTo("WH-RTM"))
            .body("warehouse.name", equalTo("Rotterdam Distribution Center"))
            .body("warehouse.city", equalTo("Rotterdam"))
            .body("source", equalTo("postgresql"));
    }

    @Test
    void testGetWarehouseById_NotFound() {
        given()
            .when().get("/api/reference/warehouses/WH-INVALID")
            .then()
            .statusCode(404)
            .body("error", containsString("not found"));
    }

    @Test
    void testSearchWarehousesByCity() {
        given()
            .queryParam("city", "Rotterdam")
            .when().get("/api/reference/warehouses/search")
            .then()
            .statusCode(200)
            .body("warehouses", hasSize(1))
            .body("warehouses[0].warehouse_id", equalTo("WH-RTM"));
    }

    // ==========================================
    // Customer Endpoints
    // ==========================================

    @Test
    void testGetCustomers_WithPagination() {
        given()
            .queryParam("limit", 3)
            .queryParam("offset", 0)
            .when().get("/api/reference/customers")
            .then()
            .statusCode(200)
            .body("customers", hasSize(3))
            .body("limit", equalTo(3))
            .body("offset", equalTo(0))
            .body("source", equalTo("postgresql"));
    }

    @Test
    void testGetCustomerById_Found() {
        given()
            .when().get("/api/reference/customers/CUST-0001")
            .then()
            .statusCode(200)
            .body("customer.customer_id", equalTo("CUST-0001"))
            .body("customer.company_name", equalTo("Test Company One"))
            .body("customer.sla_tier", equalTo("STANDARD"));
    }

    @Test
    void testGetCustomerById_NotFound() {
        given()
            .when().get("/api/reference/customers/CUST-9999")
            .then()
            .statusCode(404)
            .body("error", containsString("not found"));
    }

    @Test
    void testSearchCustomersByCompanyName() {
        given()
            .queryParam("name", "Test Company")
            .when().get("/api/reference/customers/search")
            .then()
            .statusCode(200)
            .body("customers", hasSize(4));
    }

    @Test
    void testGetCustomersBySLATier() {
        given()
            .when().get("/api/reference/customers/by-sla/EXPRESS")
            .then()
            .statusCode(200)
            .body("customers", hasSize(1))
            .body("customers[0].customer_id", equalTo("CUST-0002"));
    }

    // ==========================================
    // Vehicle Endpoints
    // ==========================================

    @Test
    void testGetAllVehicles() {
        given()
            .when().get("/api/reference/vehicles")
            .then()
            .statusCode(200)
            .body("vehicles", hasSize(3))
            .body("source", equalTo("postgresql"));
    }

    @Test
    void testGetVehicleById_Found() {
        given()
            .when().get("/api/reference/vehicles/VEH-001")
            .then()
            .statusCode(200)
            .body("vehicle.vehicle_id", equalTo("VEH-001"))
            .body("vehicle.vehicle_type", equalTo("VAN"))
            .body("vehicle.home_warehouse_id", equalTo("WH-RTM"));
    }

    @Test
    void testGetVehicleById_NotFound() {
        given()
            .when().get("/api/reference/vehicles/VEH-999")
            .then()
            .statusCode(404)
            .body("error", containsString("not found"));
    }

    @Test
    void testGetVehiclesByWarehouse() {
        given()
            .when().get("/api/reference/vehicles/by-warehouse/WH-RTM")
            .then()
            .statusCode(200)
            .body("vehicles", hasSize(1))
            .body("vehicles[0].vehicle_id", equalTo("VEH-001"));
    }

    // ==========================================
    // Driver Endpoints
    // ==========================================

    @Test
    void testGetAllDrivers() {
        given()
            .when().get("/api/reference/drivers")
            .then()
            .statusCode(200)
            .body("drivers", hasSize(3))
            .body("source", equalTo("postgresql"));
    }

    @Test
    void testGetDriverById_Found() {
        given()
            .when().get("/api/reference/drivers/DRV-001")
            .then()
            .statusCode(200)
            .body("driver.driver_id", equalTo("DRV-001"))
            .body("driver.first_name", equalTo("Jan"))
            .body("driver.last_name", equalTo("van der Berg"));
    }

    @Test
    void testGetDriverById_NotFound() {
        given()
            .when().get("/api/reference/drivers/DRV-999")
            .then()
            .statusCode(404)
            .body("error", containsString("not found"));
    }

    @Test
    void testGetAvailableDrivers() {
        given()
            .when().get("/api/reference/drivers/available")
            .then()
            .statusCode(200)
            .body("drivers", hasSize(2)); // DRV-001 and DRV-003 are AVAILABLE
    }

    // ==========================================
    // Route Endpoints
    // ==========================================

    @Test
    void testGetAllRoutes() {
        given()
            .when().get("/api/reference/routes")
            .then()
            .statusCode(200)
            .body("routes", hasSize(3))
            .body("source", equalTo("postgresql"));
    }

    @Test
    void testGetRoutesByOrigin() {
        given()
            .when().get("/api/reference/routes/from/WH-RTM")
            .then()
            .statusCode(200)
            .body("routes", hasSize(1))
            .body("routes[0].destination_city", equalTo("Amsterdam"));
    }

    @Test
    void testGetRoutesByDestination() {
        given()
            .when().get("/api/reference/routes/to/Munich")
            .then()
            .statusCode(200)
            .body("routes", hasSize(1))
            .body("routes[0].route_id", equalTo("RTE-002"));
    }

    // ==========================================
    // Product Endpoints
    // ==========================================

    @Test
    void testGetProductsByCategory() {
        given()
            .queryParam("category", "ELECTRONICS")
            .queryParam("limit", 10)
            .queryParam("offset", 0)
            .when().get("/api/reference/products")
            .then()
            .statusCode(200)
            .body("products", hasSize(1))
            .body("products[0].name", equalTo("Test Electronics Item"));
    }

    @Test
    void testSearchProducts() {
        given()
            .queryParam("search", "Test")
            .queryParam("limit", 10)
            .when().get("/api/reference/products")
            .then()
            .statusCode(200)
            .body("products", hasSize(5));
    }
}
