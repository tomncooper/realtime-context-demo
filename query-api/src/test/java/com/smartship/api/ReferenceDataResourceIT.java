package com.smartship.api;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Native image integration tests for ReferenceDataResource.
 * Runs the same tests as ReferenceDataResourceTest against the native executable.
 *
 * These tests verify that PostgreSQL queries work correctly in native mode,
 * including all the reference data endpoints for warehouses, customers,
 * vehicles, drivers, routes, and products.
 *
 * Run with: ./mvnw verify -Dnative
 */
@QuarkusIntegrationTest
public class ReferenceDataResourceIT extends ReferenceDataResourceTest {
    // Inherits all tests from ReferenceDataResourceTest
    // Tests are executed against the native executable
}
