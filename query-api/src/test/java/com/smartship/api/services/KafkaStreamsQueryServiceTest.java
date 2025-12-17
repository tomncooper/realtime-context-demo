package com.smartship.api.services;

import com.smartship.api.KafkaStreamsQueryService;
import com.smartship.api.model.StreamsInstanceMetadata;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for KafkaStreamsQueryService with mocked StreamsInstanceDiscoveryService.
 * Tests graceful degradation when streams processor is unavailable.
 */
@QuarkusTest
class KafkaStreamsQueryServiceTest {

    @InjectMock
    StreamsInstanceDiscoveryService discoveryService;

    @Inject
    KafkaStreamsQueryService service;

    @BeforeEach
    void setUp() {
        // Reset mocks before each test
        reset(discoveryService);
    }

    // ==========================================
    // Shipment Status Store Tests
    // ==========================================

    @Test
    void testGetShipmentCountByStatus_NoInstanceFound() {
        // Given no instance is available for the key
        when(discoveryService.findInstanceForKey(anyString(), anyString()))
            .thenReturn(null);

        // When querying for a status
        Long count = service.getShipmentCountByStatus("IN_TRANSIT");

        // Then should return 0 (graceful degradation)
        assertEquals(0L, count);

        // Verify the discovery service was called
        verify(discoveryService).findInstanceForKey("active-shipments-by-status", "IN_TRANSIT");
    }

    @Test
    void testGetAllStatusCounts_NoInstancesFound() {
        // Given no instances are discovered
        when(discoveryService.discoverInstances(anyString()))
            .thenReturn(List.of());

        // When querying for all status counts
        Map<String, Object> counts = service.getAllStatusCounts();

        // Then should return empty map
        assertNotNull(counts);
        assertTrue(counts.isEmpty());

        // Verify the discovery service was called
        verify(discoveryService).discoverInstances("active-shipments-by-status");
    }

    // ==========================================
    // Vehicle State Store Tests
    // ==========================================

    @Test
    void testGetVehicleState_NoInstanceFound() {
        // Given no instance is available
        when(discoveryService.findInstanceForKey(anyString(), anyString()))
            .thenReturn(null);

        // When querying for vehicle state
        Map<String, Object> state = service.getVehicleState("VEH-001");

        // Then should return null
        assertNull(state);

        verify(discoveryService).findInstanceForKey("vehicle-current-state", "VEH-001");
    }

    @Test
    void testGetAllVehicleStates_NoInstancesFound() {
        // Given no instances are discovered
        when(discoveryService.discoverInstances(anyString()))
            .thenReturn(List.of());

        // When querying for all vehicle states
        List<Map<String, Object>> states = service.getAllVehicleStates();

        // Then should return empty list
        assertNotNull(states);
        assertTrue(states.isEmpty());

        verify(discoveryService).discoverInstances("vehicle-current-state");
    }

    // ==========================================
    // Customer Shipments Store Tests
    // ==========================================

    @Test
    void testGetCustomerShipmentStats_NoInstanceFound() {
        // Given no instance is available
        when(discoveryService.findInstanceForKey(anyString(), anyString()))
            .thenReturn(null);

        // When querying for customer shipment stats
        Map<String, Object> stats = service.getCustomerShipmentStats("CUST-0001");

        // Then should return null
        assertNull(stats);

        verify(discoveryService).findInstanceForKey("shipments-by-customer", "CUST-0001");
    }

    @Test
    void testGetAllCustomerStats_NoInstancesFound() {
        // Given no instances are discovered
        when(discoveryService.discoverInstances(anyString()))
            .thenReturn(List.of());

        // When querying for all customer stats
        List<Map<String, Object>> stats = service.getAllCustomerStats();

        // Then should return empty list
        assertNotNull(stats);
        assertTrue(stats.isEmpty());

        verify(discoveryService).discoverInstances("shipments-by-customer");
    }

    // ==========================================
    // Late Shipments Store Tests
    // ==========================================

    @Test
    void testGetLateShipment_NoInstanceFound() {
        // Given no instance is available
        when(discoveryService.findInstanceForKey(anyString(), anyString()))
            .thenReturn(null);

        // When querying for late shipment
        Map<String, Object> shipment = service.getLateShipment("SH-12345678");

        // Then should return null
        assertNull(shipment);

        verify(discoveryService).findInstanceForKey("late-shipments", "SH-12345678");
    }

    @Test
    void testGetAllLateShipments_NoInstancesFound() {
        // Given no instances are discovered
        when(discoveryService.discoverInstances(anyString()))
            .thenReturn(List.of());

        // When querying for all late shipments
        List<Map<String, Object>> shipments = service.getAllLateShipments();

        // Then should return empty list
        assertNotNull(shipments);
        assertTrue(shipments.isEmpty());

        verify(discoveryService).discoverInstances("late-shipments");
    }

    // ==========================================
    // Warehouse Metrics Store Tests
    // ==========================================

    @Test
    void testGetWarehouseMetrics_NoInstancesFound() {
        // Given no instances are discovered (windowed stores query all instances)
        when(discoveryService.discoverInstances(anyString()))
            .thenReturn(List.of());

        // When querying for warehouse metrics
        List<Map<String, Object>> metrics = service.getWarehouseMetrics("WH-RTM");

        // Then should return empty list
        assertNotNull(metrics);
        assertTrue(metrics.isEmpty());

        verify(discoveryService).discoverInstances("warehouse-realtime-metrics");
    }

    @Test
    void testGetAllWarehouseMetrics_NoInstancesFound() {
        // Given no instances are discovered
        when(discoveryService.discoverInstances(anyString()))
            .thenReturn(List.of());

        // When querying for all warehouse metrics
        Map<String, Object> metrics = service.getAllWarehouseMetrics();

        // Then should return empty map
        assertNotNull(metrics);
        assertTrue(metrics.isEmpty());

        verify(discoveryService).discoverInstances("warehouse-realtime-metrics");
    }

    // ==========================================
    // Hourly Performance Store Tests
    // ==========================================

    @Test
    void testGetHourlyPerformance_NoInstancesFound() {
        // Given no instances are discovered (windowed stores query all instances)
        when(discoveryService.discoverInstances(anyString()))
            .thenReturn(List.of());

        // When querying for hourly performance
        List<Map<String, Object>> performance = service.getHourlyPerformance("WH-RTM");

        // Then should return empty list
        assertNotNull(performance);
        assertTrue(performance.isEmpty());

        verify(discoveryService).discoverInstances("hourly-delivery-performance");
    }

    @Test
    void testGetAllHourlyPerformance_NoInstancesFound() {
        // Given no instances are discovered
        when(discoveryService.discoverInstances(anyString()))
            .thenReturn(List.of());

        // When querying for all hourly performance
        Map<String, Object> performance = service.getAllHourlyPerformance();

        // Then should return empty map
        assertNotNull(performance);
        assertTrue(performance.isEmpty());

        verify(discoveryService).discoverInstances("hourly-delivery-performance");
    }

    // ==========================================
    // Order State Store Tests (Phase 4)
    // ==========================================

    @Test
    void testGetOrderState_NoInstanceFound() {
        // Given no instance is available
        when(discoveryService.findInstanceForKey(anyString(), anyString()))
            .thenReturn(null);

        // When querying for order state
        Map<String, Object> state = service.getOrderState("ORD-0001");

        // Then should return null
        assertNull(state);

        verify(discoveryService).findInstanceForKey("order-current-state", "ORD-0001");
    }

    @Test
    void testGetCustomerOrderStats_NoInstanceFound() {
        // Given no instance is available
        when(discoveryService.findInstanceForKey(anyString(), anyString()))
            .thenReturn(null);

        // When querying for customer order stats
        Map<String, Object> stats = service.getCustomerOrderStats("CUST-0001");

        // Then should return null
        assertNull(stats);

        verify(discoveryService).findInstanceForKey("orders-by-customer", "CUST-0001");
    }

    @Test
    void testGetOrdersAtSLARisk_NoInstancesFound() {
        // Given no instances are discovered
        when(discoveryService.discoverInstances(anyString()))
            .thenReturn(List.of());

        // When querying for orders at SLA risk
        List<Map<String, Object>> orders = service.getOrdersAtSLARisk();

        // Then should return empty list
        assertNotNull(orders);
        assertTrue(orders.isEmpty());

        verify(discoveryService).discoverInstances("order-sla-tracking");
    }

    // ==========================================
    // Discovery Service Integration Tests
    // ==========================================

    @Test
    void testDiscoveryServiceCalledWithCorrectStoreName() {
        // Given a mock that tracks store names
        when(discoveryService.discoverInstances(anyString()))
            .thenReturn(List.of());

        // When querying different stores
        service.getAllStatusCounts();
        service.getAllVehicleStates();
        service.getAllCustomerStats();

        // Then verify correct store names were used
        verify(discoveryService).discoverInstances("active-shipments-by-status");
        verify(discoveryService).discoverInstances("vehicle-current-state");
        verify(discoveryService).discoverInstances("shipments-by-customer");
    }
}
