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
 * Integration tests for HybridQueryResource REST endpoints.
 * Tests hybrid queries combining PostgreSQL reference data and Kafka Streams real-time data.
 * Mocks StreamsInstanceDiscoveryService to simulate various Kafka Streams states.
 */
@QuarkusTest
class HybridQueryResourceTest {

    @InjectMock
    StreamsInstanceDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        // Default: streams processor is unavailable
        when(discoveryService.discoverInstances(anyString()))
            .thenReturn(List.of());
        when(discoveryService.findInstanceForKey(anyString(), anyString()))
            .thenReturn(null);
    }

    // ==========================================
    // Customer Overview (Hybrid Query)
    // ==========================================

    @Test
    void testGetCustomerOverview_Found_StreamsUnavailable() {
        // Customer exists in PostgreSQL, but Kafka Streams is unavailable
        // Response uses snake_case field names (Jackson serialization)
        given()
            .when().get("/api/hybrid/customers/CUST-0001/overview")
            .then()
            .statusCode(200)
            .body("result.customer_id", equalTo("CUST-0001"))
            .body("result.company_name", equalTo("Test Company One"))
            .body("result.sla_tier", equalTo("STANDARD"))
            .body("sources", hasItem("postgresql"));
    }

    @Test
    void testGetCustomerOverview_NotFound() {
        // Customer does not exist - hybrid query returns 200 with null result
        given()
            .when().get("/api/hybrid/customers/CUST-9999/overview")
            .then()
            .statusCode(200)
            .body("result", nullValue())
            .body("summary", containsString("not found"));
    }

    // ==========================================
    // Customer SLA Compliance (Hybrid Query)
    // ==========================================

    @Test
    void testGetCustomerSLACompliance_Found() {
        given()
            .when().get("/api/hybrid/customers/CUST-0002/sla-compliance")
            .then()
            .statusCode(200)
            .body("result.customer_id", equalTo("CUST-0002"))
            .body("result.sla_tier", equalTo("EXPRESS"))
            .body("sources", hasItem("postgresql"));
    }

    @Test
    void testGetCustomerSLACompliance_NotFound() {
        // Customer does not exist - returns error map in result
        given()
            .when().get("/api/hybrid/customers/CUST-9999/sla-compliance")
            .then()
            .statusCode(200)
            .body("result.error", containsString("not found"));
    }

    // ==========================================
    // Enriched Vehicle State (Hybrid Query)
    // ==========================================

    @Test
    void testGetEnrichedVehicleState_StreamsUnavailable() {
        // Enriched vehicle state requires Kafka Streams real-time data
        // When streams are unavailable, returns null result with summary message
        given()
            .when().get("/api/hybrid/vehicles/VEH-001/enriched")
            .then()
            .statusCode(200)
            .body("result", nullValue())
            .body("summary", containsString("Vehicle"));
    }

    @Test
    void testGetEnrichedVehicleState_NotFound() {
        // Vehicle does not exist - also returns null result since streams unavailable
        given()
            .when().get("/api/hybrid/vehicles/VEH-999/enriched")
            .then()
            .statusCode(200)
            .body("result", nullValue());
    }

    // ==========================================
    // Driver Tracking (Hybrid Query)
    // ==========================================

    @Test
    void testGetDriverTracking_Found() {
        // Driver tracking includes PostgreSQL data and Kafka Streams vehicle state
        // When driver exists but streams unavailable, returns driver info without vehicle state
        given()
            .when().get("/api/hybrid/drivers/DRV-001/tracking")
            .then()
            .statusCode(200)
            .body("result.driver_id", equalTo("DRV-001"))
            .body("sources", hasItem("postgresql"));
    }

    @Test
    void testGetDriverTracking_NotFound() {
        // Driver does not exist - returns error map in result
        given()
            .when().get("/api/hybrid/drivers/DRV-999/tracking")
            .then()
            .statusCode(200)
            .body("result.error", containsString("not found"));
    }

    // ==========================================
    // Warehouse Status (Hybrid Query)
    // ==========================================

    @Test
    void testGetWarehouseStatus_Found() {
        given()
            .when().get("/api/hybrid/warehouses/WH-RTM/status")
            .then()
            .statusCode(200)
            .body("result.warehouse_id", equalTo("WH-RTM"))
            .body("result.name", equalTo("Rotterdam Distribution Center"))
            .body("sources", hasItem("postgresql"));
    }

    @Test
    void testGetWarehouseStatus_NotFound() {
        // Warehouse does not exist - returns error map in result
        given()
            .when().get("/api/hybrid/warehouses/WH-INVALID/status")
            .then()
            .statusCode(200)
            .body("result.error", containsString("not found"));
    }

    // ==========================================
    // Verify Hybrid Query Response Structure
    // ==========================================

    @Test
    void testHybridQueryResponseStructure() {
        given()
            .when().get("/api/hybrid/customers/CUST-0001/overview")
            .then()
            .statusCode(200)
            .body("$", hasKey("result"))
            .body("$", hasKey("sources"))
            .body("$", hasKey("warnings"))
            .body("$", hasKey("query_time_ms"))
            .body("$", hasKey("timestamp"))
            .body("$", hasKey("summary"));
    }
}
