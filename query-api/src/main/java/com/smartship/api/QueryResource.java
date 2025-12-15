package com.smartship.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for querying logistics data from Kafka Streams state stores.
 * Phase 4: Supports all 9 state stores including order state stores.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class QueryResource {

    private static final Logger LOG = Logger.getLogger(QueryResource.class);

    @Inject
    KafkaStreamsQueryService streamsQuery;

    // ===========================================
    // Shipment Status Endpoints
    // ===========================================

    @GET
    @Path("/shipments/by-status/{status}")
    @Tag(name = "Shipments", description = "Query shipment data")
    @Operation(summary = "Get shipment count by status",
               description = "Returns the count of shipments for a specific status")
    public Response getShipmentsByStatus(@PathParam("status") String status) {
        LOG.infof("Querying shipments with status: %s", status);

        try {
            Long count = streamsQuery.getShipmentCountByStatus(status);

            Map<String, Object> response = Map.of(
                "status", status,
                "count", count,
                "timestamp", System.currentTimeMillis(),
                "source", "kafka-streams"
            );

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.error("Error querying shipments by status", e);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/shipments/status/all")
    @Tag(name = "Shipments")
    @Operation(summary = "Get all status counts",
               description = "Returns counts for all shipment statuses")
    public Response getAllStatusCounts() {
        LOG.info("Querying all status counts");

        try {
            Map<String, Object> counts = streamsQuery.getAllStatusCounts();
            Map<String, Object> response = new HashMap<>();
            response.put("counts", counts);
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.error("Error querying all status counts", e);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    // ===========================================
    // Vehicle State Endpoints
    // ===========================================

    @GET
    @Path("/vehicles/state")
    @Tag(name = "Vehicles", description = "Query vehicle telemetry data")
    @Operation(summary = "Get all vehicle states",
               description = "Returns current state for all vehicles from telemetry")
    public Response getAllVehicleStates() {
        LOG.info("Querying all vehicle states");

        try {
            List<Map<String, Object>> vehicles = streamsQuery.getAllVehicleStates();

            Map<String, Object> response = new HashMap<>();
            response.put("vehicles", vehicles);
            response.put("count", vehicles.size());
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.error("Error querying all vehicle states", e);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/vehicles/state/{vehicleId}")
    @Tag(name = "Vehicles")
    @Operation(summary = "Get vehicle state",
               description = "Returns current state for a specific vehicle")
    public Response getVehicleState(@PathParam("vehicleId") String vehicleId) {
        LOG.infof("Querying vehicle state for: %s", vehicleId);

        try {
            Map<String, Object> state = streamsQuery.getVehicleState(vehicleId);

            if (state == null || state.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Vehicle not found: " + vehicleId))
                    .build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("vehicle", state);
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error querying vehicle state for: %s", vehicleId);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    // ===========================================
    // Customer Shipment Stats Endpoints
    // ===========================================

    @GET
    @Path("/customers/shipments/all")
    @Tag(name = "Customers", description = "Query customer shipment data")
    @Operation(summary = "Get all customer shipment stats",
               description = "Returns shipment statistics for all customers")
    public Response getAllCustomerStats() {
        LOG.info("Querying all customer shipment stats");

        try {
            List<Map<String, Object>> customers = streamsQuery.getAllCustomerStats();

            Map<String, Object> response = new HashMap<>();
            response.put("customers", customers);
            response.put("count", customers.size());
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.error("Error querying all customer stats", e);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/customers/{customerId}/shipments")
    @Tag(name = "Customers")
    @Operation(summary = "Get customer shipment stats",
               description = "Returns shipment statistics for a specific customer")
    public Response getCustomerShipmentStats(@PathParam("customerId") String customerId) {
        LOG.infof("Querying shipment stats for customer: %s", customerId);

        try {
            Map<String, Object> stats = streamsQuery.getCustomerShipmentStats(customerId);

            if (stats == null || stats.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Customer not found: " + customerId))
                    .build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("customer_stats", stats);
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error querying customer stats for: %s", customerId);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    // ===========================================
    // Late Shipments Endpoints
    // ===========================================

    @GET
    @Path("/shipments/late")
    @Tag(name = "Shipments")
    @Operation(summary = "Get all late shipments",
               description = "Returns all shipments that are past their expected delivery (30-min grace)")
    public Response getAllLateShipments() {
        LOG.info("Querying all late shipments");

        try {
            List<Map<String, Object>> lateShipments = streamsQuery.getAllLateShipments();

            Map<String, Object> response = new HashMap<>();
            response.put("late_shipments", lateShipments);
            response.put("count", lateShipments.size());
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.error("Error querying all late shipments", e);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/shipments/late/{shipmentId}")
    @Tag(name = "Shipments")
    @Operation(summary = "Get late shipment details",
               description = "Returns details for a specific late shipment")
    public Response getLateShipment(@PathParam("shipmentId") String shipmentId) {
        LOG.infof("Querying late shipment: %s", shipmentId);

        try {
            Map<String, Object> details = streamsQuery.getLateShipment(shipmentId);

            if (details == null || details.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Late shipment not found: " + shipmentId))
                    .build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("shipment", details);
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error querying late shipment: %s", shipmentId);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    // ===========================================
    // Warehouse Metrics Endpoints
    // ===========================================

    @GET
    @Path("/warehouses/metrics/all")
    @Tag(name = "Warehouses", description = "Query warehouse operations data")
    @Operation(summary = "Get all warehouse metrics",
               description = "Returns real-time metrics for all warehouses (15-min window)")
    public Response getAllWarehouseMetrics() {
        LOG.info("Querying all warehouse metrics");

        try {
            Map<String, Object> metrics = streamsQuery.getAllWarehouseMetrics();

            Map<String, Object> response = new HashMap<>();
            response.put("warehouses", metrics);
            response.put("window_size_minutes", 15);
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.error("Error querying all warehouse metrics", e);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/warehouses/{warehouseId}/metrics")
    @Tag(name = "Warehouses")
    @Operation(summary = "Get warehouse metrics",
               description = "Returns real-time metrics for a specific warehouse")
    public Response getWarehouseMetrics(@PathParam("warehouseId") String warehouseId) {
        LOG.infof("Querying warehouse metrics for: %s", warehouseId);

        try {
            List<Map<String, Object>> metrics = streamsQuery.getWarehouseMetrics(warehouseId);

            if (metrics.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No metrics found for warehouse: " + warehouseId))
                    .build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("warehouse_id", warehouseId);
            response.put("windows", metrics);
            response.put("window_size_minutes", 15);
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error querying warehouse metrics for: %s", warehouseId);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    // ===========================================
    // Delivery Performance Endpoints
    // ===========================================

    @GET
    @Path("/performance/hourly")
    @Tag(name = "Performance", description = "Query delivery performance data")
    @Operation(summary = "Get all hourly performance",
               description = "Returns hourly delivery performance for all warehouses")
    public Response getAllHourlyPerformance() {
        LOG.info("Querying all hourly performance");

        try {
            Map<String, Object> performance = streamsQuery.getAllHourlyPerformance();

            Map<String, Object> response = new HashMap<>();
            response.put("warehouses", performance);
            response.put("window_size_minutes", 60);
            response.put("window_advance_minutes", 30);
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.error("Error querying all hourly performance", e);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/performance/hourly/{warehouseId}")
    @Tag(name = "Performance")
    @Operation(summary = "Get warehouse hourly performance",
               description = "Returns hourly delivery performance for a specific warehouse")
    public Response getHourlyPerformance(@PathParam("warehouseId") String warehouseId) {
        LOG.infof("Querying hourly performance for: %s", warehouseId);

        try {
            List<Map<String, Object>> performance = streamsQuery.getHourlyPerformance(warehouseId);

            if (performance.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No performance data found for warehouse: " + warehouseId))
                    .build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("warehouse_id", warehouseId);
            response.put("windows", performance);
            response.put("window_size_minutes", 60);
            response.put("window_advance_minutes", 30);
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error querying hourly performance for: %s", warehouseId);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    // ===========================================
    // Order State Endpoints (Phase 4)
    // ===========================================

    @GET
    @Path("/orders/state")
    @Tag(name = "Orders", description = "Query order state data")
    @Operation(summary = "Get all order states",
               description = "Returns current state for all orders")
    public Response getAllOrderStates() {
        LOG.info("Querying all order states");

        try {
            List<Map<String, Object>> orders = streamsQuery.getAllOrderStates();

            Map<String, Object> response = new HashMap<>();
            response.put("orders", orders);
            response.put("count", orders.size());
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.error("Error querying all order states", e);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/orders/state/{orderId}")
    @Tag(name = "Orders")
    @Operation(summary = "Get order state",
               description = "Returns current state for a specific order")
    public Response getOrderState(@PathParam("orderId") String orderId) {
        LOG.infof("Querying order state for: %s", orderId);

        try {
            Map<String, Object> state = streamsQuery.getOrderState(orderId);

            if (state == null || state.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Order not found: " + orderId))
                    .build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("order", state);
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error querying order state for: %s", orderId);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    // ===========================================
    // Customer Order Stats Endpoints (Phase 4)
    // ===========================================

    @GET
    @Path("/orders/by-customer/all")
    @Tag(name = "Orders")
    @Operation(summary = "Get all customer order stats",
               description = "Returns order statistics for all customers")
    public Response getAllCustomerOrderStats() {
        LOG.info("Querying all customer order stats");

        try {
            List<Map<String, Object>> stats = streamsQuery.getAllCustomerOrderStats();

            Map<String, Object> response = new HashMap<>();
            response.put("customers", stats);
            response.put("count", stats.size());
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.error("Error querying all customer order stats", e);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/orders/by-customer/{customerId}")
    @Tag(name = "Orders")
    @Operation(summary = "Get customer order stats",
               description = "Returns order statistics for a specific customer")
    public Response getCustomerOrderStats(@PathParam("customerId") String customerId) {
        LOG.infof("Querying order stats for customer: %s", customerId);

        try {
            Map<String, Object> stats = streamsQuery.getCustomerOrderStats(customerId);

            if (stats == null || stats.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Customer orders not found: " + customerId))
                    .build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("customer_order_stats", stats);
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error querying customer order stats for: %s", customerId);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    // ===========================================
    // Order SLA Tracking Endpoints (Phase 4)
    // ===========================================

    @GET
    @Path("/orders/sla-risk")
    @Tag(name = "Orders")
    @Operation(summary = "Get orders at SLA risk",
               description = "Returns all orders approaching or past SLA deadline")
    public Response getOrdersAtSLARisk() {
        LOG.info("Querying orders at SLA risk");

        try {
            List<Map<String, Object>> atRisk = streamsQuery.getOrdersAtSLARisk();

            Map<String, Object> response = new HashMap<>();
            response.put("at_risk_orders", atRisk);
            response.put("count", atRisk.size());
            response.put("sla_risk_threshold_minutes", 60);
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.error("Error querying orders at SLA risk", e);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/orders/sla-risk/{orderId}")
    @Tag(name = "Orders")
    @Operation(summary = "Get order SLA tracking",
               description = "Returns SLA tracking details for a specific order")
    public Response getOrderSLATracking(@PathParam("orderId") String orderId) {
        LOG.infof("Querying SLA tracking for order: %s", orderId);

        try {
            Map<String, Object> tracking = streamsQuery.getOrderSLATracking(orderId);

            if (tracking == null || tracking.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Order SLA tracking not found: " + orderId))
                    .build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("sla_tracking", tracking);
            response.put("timestamp", System.currentTimeMillis());
            response.put("source", "kafka-streams");

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error querying SLA tracking for order: %s", orderId);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    // ===========================================
    // Health Check
    // ===========================================

    @GET
    @Path("/health")
    @Tag(name = "Health")
    @Operation(summary = "Health check", description = "Check if the query API is healthy")
    public Response health() {
        return Response.ok(Map.of(
            "status", "UP",
            "service", "smartship-query-api",
            "phase", "4",
            "state_stores", List.of(
                "active-shipments-by-status",
                "vehicle-current-state",
                "shipments-by-customer",
                "late-shipments",
                "warehouse-realtime-metrics",
                "hourly-delivery-performance",
                "order-current-state",
                "orders-by-customer",
                "order-sla-tracking"
            ),
            "timestamp", System.currentTimeMillis()
        )).build();
    }
}
