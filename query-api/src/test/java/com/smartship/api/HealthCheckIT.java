package com.smartship.api;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Native image integration test for health endpoints.
 * Verifies the native image starts correctly and health checks pass.
 *
 * These tests run against the native executable built with:
 * ./mvnw verify -Dnative
 */
@QuarkusIntegrationTest
public class HealthCheckIT {

    @Test
    void testLivenessCheck() {
        given()
            .when().get("/q/health/live")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"));
    }

    @Test
    void testReadinessCheck() {
        given()
            .when().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"));
    }

    @Test
    void testApplicationHealth() {
        given()
            .when().get("/api/health")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
            .body("phase", equalTo("4"));
    }

    @Test
    void testOpenAPIEndpoint() {
        given()
            .when().get("/q/openapi")
            .then()
            .statusCode(200)
            .contentType("application/yaml");
    }
}
