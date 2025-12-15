package com.smartship.api;

import com.smartship.api.model.hybrid.*;
import com.smartship.api.services.QueryOrchestrationService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * REST API for hybrid queries combining Kafka Streams and PostgreSQL data.
 * Phase 4: Provides enriched, multi-source query capability for LLM integration.
 */
@Path("/api/hybrid")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Hybrid Queries", description = "Multi-source queries combining real-time and reference data")
public class HybridQueryResource {

    private static final Logger LOG = Logger.getLogger(HybridQueryResource.class);

    @Inject
    QueryOrchestrationService orchestrationService;

    // ===========================================
    // Customer Hybrid Endpoints
    // ===========================================

    @GET
    @Path("/customers/{customerId}/overview")
    @Operation(
        summary = "Get customer overview",
        description = "Returns comprehensive customer profile with real-time shipment and order statistics from Kafka Streams combined with reference data from PostgreSQL"
    )
    public Uni<HybridQueryResult<EnrichedCustomerOverview>> getCustomerOverview(
            @PathParam("customerId") String customerId) {
        LOG.infof("Hybrid query: customer overview for %s", customerId);
        return orchestrationService.getCustomerOverview(customerId);
    }

    @GET
    @Path("/customers/by-name/{companyName}/late-shipments")
    @Operation(
        summary = "Get late shipments for company",
        description = "Finds customers by company name and returns their late shipments with enriched details"
    )
    public Uni<HybridQueryResult<List<EnrichedLateShipment>>> getLateShipmentsForCompany(
            @PathParam("companyName") String companyName) {
        LOG.infof("Hybrid query: late shipments for company %s", companyName);
        return orchestrationService.getLateShipmentsForCompany(companyName);
    }

    @GET
    @Path("/customers/{customerId}/sla-compliance")
    @Operation(
        summary = "Get customer SLA compliance",
        description = "Returns detailed SLA compliance metrics for a customer including at-risk orders"
    )
    public Uni<HybridQueryResult<Map<String, Object>>> getCustomerSLACompliance(
            @PathParam("customerId") String customerId) {
        LOG.infof("Hybrid query: SLA compliance for customer %s", customerId);
        return orchestrationService.getCustomerSLACompliance(customerId);
    }

    // ===========================================
    // Vehicle/Logistics Hybrid Endpoints
    // ===========================================

    @GET
    @Path("/vehicles/{vehicleId}/enriched")
    @Operation(
        summary = "Get enriched vehicle state",
        description = "Returns real-time vehicle telemetry enriched with reference data (vehicle specs, driver info, warehouse details)"
    )
    public Uni<HybridQueryResult<EnrichedVehicleState>> getEnrichedVehicleState(
            @PathParam("vehicleId") String vehicleId) {
        LOG.infof("Hybrid query: enriched vehicle state for %s", vehicleId);
        return orchestrationService.getEnrichedVehicleState(vehicleId);
    }

    @GET
    @Path("/drivers/{driverId}/tracking")
    @Operation(
        summary = "Get driver tracking info",
        description = "Returns driver profile with current vehicle state and location"
    )
    public Uni<HybridQueryResult<Map<String, Object>>> getDriverTracking(
            @PathParam("driverId") String driverId) {
        LOG.infof("Hybrid query: driver tracking for %s", driverId);
        return orchestrationService.getDriverWithVehicleState(driverId);
    }

    // ===========================================
    // Warehouse Hybrid Endpoints
    // ===========================================

    @GET
    @Path("/warehouses/{warehouseId}/status")
    @Operation(
        summary = "Get warehouse operational status",
        description = "Returns warehouse details with real-time metrics, vehicle counts, and driver availability"
    )
    public Uni<HybridQueryResult<Map<String, Object>>> getWarehouseStatus(
            @PathParam("warehouseId") String warehouseId) {
        LOG.infof("Hybrid query: warehouse status for %s", warehouseId);
        return orchestrationService.getWarehouseOperationalStatus(warehouseId);
    }

    // ===========================================
    // Order Hybrid Endpoints
    // ===========================================

    @GET
    @Path("/orders/{orderId}/details")
    @Operation(
        summary = "Get order details",
        description = "Returns order state with customer information and SLA tracking details"
    )
    public Uni<HybridQueryResult<Map<String, Object>>> getOrderDetails(
            @PathParam("orderId") String orderId) {
        LOG.infof("Hybrid query: order details for %s", orderId);
        return orchestrationService.getOrderWithShipments(orderId);
    }
}
