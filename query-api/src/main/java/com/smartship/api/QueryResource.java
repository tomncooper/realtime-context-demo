package com.smartship.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * REST API for querying shipment data.
 * Phase 1: Query shipments by status from Kafka Streams state store.
 */
@Path("/api/shipments")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Shipments", description = "Query shipment data")
public class QueryResource {

    private static final Logger LOG = Logger.getLogger(QueryResource.class);

    @Inject
    KafkaStreamsQueryService streamsQuery;

    @GET
    @Path("/by-status/{status}")
    @Operation(summary = "Get shipment count by status", description = "Returns the count of shipments for a specific status (CREATED, IN_TRANSIT, DELIVERED)")
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
    @Path("/status/all")
    @Operation(summary = "Get all status counts", description = "Returns counts for all shipment statuses")
    public Response getAllStatusCounts() {
        LOG.info("Querying all status counts");

        try {
            Map<String, Object> counts = streamsQuery.getAllStatusCounts();
            counts.put("timestamp", System.currentTimeMillis());
            counts.put("source", "kafka-streams");

            return Response.ok(counts).build();

        } catch (Exception e) {
            LOG.error("Error querying all status counts", e);
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/health")
    @Operation(summary = "Health check", description = "Check if the query API is healthy")
    public Response health() {
        return Response.ok(Map.of(
            "status", "UP",
            "service", "smartship-query-api",
            "timestamp", System.currentTimeMillis()
        )).build();
    }
}
