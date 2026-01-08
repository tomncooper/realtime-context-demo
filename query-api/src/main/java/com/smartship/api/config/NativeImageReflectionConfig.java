package com.smartship.api.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers all DTO and model classes for GraalVM native image reflection.
 * This ensures JSON serialization/deserialization works correctly in native mode.
 *
 * Classes registered here are those used in:
 * - REST API responses (Jackson JSON serialization)
 * - HTTP client responses (Jackson JSON deserialization)
 * - Dynamic object creation at runtime
 */
@RegisterForReflection(targets = {
    // Core models
    com.smartship.api.model.StreamsInstanceMetadata.class,
    com.smartship.api.model.VehicleStateResponse.class,
    com.smartship.api.model.CustomerShipmentStatsResponse.class,
    com.smartship.api.model.WarehouseMetricsResponse.class,
    com.smartship.api.model.LateShipmentResponse.class,
    com.smartship.api.model.DeliveryStatsResponse.class,
    com.smartship.api.model.WindowedResponse.class,

    // Reference DTOs (PostgreSQL data)
    com.smartship.api.model.reference.CustomerDto.class,
    com.smartship.api.model.reference.DriverDto.class,
    com.smartship.api.model.reference.ProductDto.class,
    com.smartship.api.model.reference.RouteDto.class,
    com.smartship.api.model.reference.VehicleRefDto.class,
    com.smartship.api.model.reference.WarehouseDto.class,

    // Hybrid models (combined Kafka Streams + PostgreSQL data)
    com.smartship.api.model.hybrid.EnrichedCustomerOverview.class,
    com.smartship.api.model.hybrid.EnrichedCustomerOverview.Builder.class,
    com.smartship.api.model.hybrid.EnrichedLateShipment.class,
    com.smartship.api.model.hybrid.EnrichedLateShipment.Builder.class,
    com.smartship.api.model.hybrid.EnrichedVehicleState.class,
    com.smartship.api.model.hybrid.EnrichedVehicleState.Builder.class,
    com.smartship.api.model.hybrid.HybridQueryResult.class,

    // AI Chat models (Phase 6)
    com.smartship.api.ai.ChatRequest.class,
    com.smartship.api.ai.ChatResponse.class
})
public class NativeImageReflectionConfig {
    // Configuration class - no implementation needed
    // The @RegisterForReflection annotation handles everything
}
