package com.smartship.api;

import com.smartship.api.services.StreamsInstanceDiscoveryService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration tests for QueryResource REST endpoints.
 * Mocks the StreamsInstanceDiscoveryService to simulate Kafka Streams unavailability.
 */
@QuarkusTest
class QueryResourceTest {

    @InjectMock
    StreamsInstanceDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        // Default: streams processor is unavailable (returns empty lists)
        when(discoveryService.discoverInstances(anyString()))
            .thenReturn(List.of());
        when(discoveryService.findInstanceForKey(anyString(), anyString()))
            .thenReturn(null);
    }

    // ==========================================
    // Health Endpoint
    // ==========================================

    @Test
    void testHealthEndpoint() {
        given()
            .when().get("/api/health")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
            .body("phase", equalTo("4"))
            .body("timestamp", notNullValue());
    }

    // ==========================================
    // Shipment Status Endpoints
    // ==========================================

    @Test
    void testGetAllShipmentStatuses_StreamsUnavailable() {
        given()
            .when().get("/api/shipments/status/all")
            .then()
            .statusCode(200)
            .body("counts", anEmptyMap())
            .body("source", equalTo("kafka-streams"));
    }

    @Test
    void testGetShipmentsByStatus_StreamsUnavailable() {
        given()
            .when().get("/api/shipments/by-status/IN_TRANSIT")
            .then()
            .statusCode(200)
            .body("status", equalTo("IN_TRANSIT"))
            .body("count", equalTo(0))
            .body("source", equalTo("kafka-streams"));
    }

    // ==========================================
    // Vehicle State Endpoints
    // ==========================================

    @Test
    void testGetAllVehicleStates_StreamsUnavailable() {
        given()
            .when().get("/api/vehicles/state")
            .then()
            .statusCode(200)
            .body("source", equalTo("kafka-streams"));
    }

    @Test
    void testGetVehicleState_NotFound() {
        given()
            .when().get("/api/vehicles/state/VEH-001")
            .then()
            .statusCode(404)
            .body("error", containsString("not found"));
    }

    // ==========================================
    // Customer Shipment Endpoints
    // ==========================================

    @Test
    void testGetAllCustomerShipmentStats_StreamsUnavailable() {
        given()
            .when().get("/api/customers/shipments/all")
            .then()
            .statusCode(200)
            .body("source", equalTo("kafka-streams"));
    }

    @Test
    void testGetCustomerShipmentStats_NotFound() {
        given()
            .when().get("/api/customers/CUST-0001/shipments")
            .then()
            .statusCode(404)
            .body("error", containsString("not found"));
    }

    // ==========================================
    // Late Shipments Endpoints
    // ==========================================

    @Test
    void testGetLateShipments_StreamsUnavailable() {
        given()
            .when().get("/api/shipments/late")
            .then()
            .statusCode(200)
            .body("late_shipments", empty())
            .body("source", equalTo("kafka-streams"));
    }

    // ==========================================
    // Warehouse Metrics Endpoints
    // ==========================================

    @Test
    void testGetAllWarehouseMetrics_StreamsUnavailable() {
        given()
            .when().get("/api/warehouses/metrics/all")
            .then()
            .statusCode(200)
            .body("source", equalTo("kafka-streams"));
    }

    @Test
    void testGetWarehouseMetrics_Empty() {
        // When streams processor is unavailable, returns 404 (no metrics found)
        given()
            .when().get("/api/warehouses/WH-RTM/metrics")
            .then()
            .statusCode(404)
            .body("error", containsString("No metrics found"));
    }

    // ==========================================
    // Performance Endpoints
    // ==========================================

    @Test
    void testGetAllHourlyPerformance_StreamsUnavailable() {
        given()
            .when().get("/api/performance/hourly")
            .then()
            .statusCode(200)
            .body("source", equalTo("kafka-streams"));
    }

    @Test
    void testGetWarehouseHourlyPerformance_Empty() {
        // When streams processor is unavailable, returns 404 (no performance data found)
        given()
            .when().get("/api/performance/hourly/WH-RTM")
            .then()
            .statusCode(404)
            .body("error", containsString("No performance"));
    }

    // ==========================================
    // Order Endpoints (Phase 4)
    // ==========================================

    @Test
    void testGetAllOrderStates_StreamsUnavailable() {
        given()
            .when().get("/api/orders/state")
            .then()
            .statusCode(200)
            .body("source", equalTo("kafka-streams"));
    }

    @Test
    void testGetOrderState_NotFound() {
        // When streams processor is unavailable, order state returns 404
        given()
            .when().get("/api/orders/state/ORD-0001")
            .then()
            .statusCode(404)
            .body("error", containsString("not found"));
    }

    @Test
    void testGetOrdersByCustomer_StreamsUnavailable() {
        given()
            .when().get("/api/orders/by-customer/all")
            .then()
            .statusCode(200)
            .body("source", equalTo("kafka-streams"));
    }

    @Test
    void testGetSLARiskOrders_StreamsUnavailable() {
        given()
            .when().get("/api/orders/sla-risk")
            .then()
            .statusCode(200)
            .body("at_risk_orders", empty())
            .body("source", equalTo("kafka-streams"));
    }
}
