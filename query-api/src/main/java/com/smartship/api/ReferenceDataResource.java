package com.smartship.api;

import com.smartship.api.model.reference.*;
import com.smartship.api.services.PostgresQueryService;
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
 * REST API for querying PostgreSQL reference data.
 * Phase 4: Provides access to all 6 reference tables.
 */
@Path("/api/reference")
@Produces(MediaType.APPLICATION_JSON)
public class ReferenceDataResource {

    private static final Logger LOG = Logger.getLogger(ReferenceDataResource.class);

    @Inject
    PostgresQueryService postgresQuery;

    // ===========================================
    // Warehouse Endpoints
    // ===========================================

    @GET
    @Path("/warehouses")
    @Tag(name = "Reference - Warehouses", description = "Query warehouse reference data")
    @Operation(summary = "Get all warehouses",
               description = "Returns all warehouse locations")
    public Uni<Map<String, Object>> getAllWarehouses() {
        LOG.info("Querying all warehouses");

        return postgresQuery.getAllWarehouses()
            .onItem().transform(warehouses -> Map.of(
                "warehouses", warehouses,
                "count", warehouses.size(),
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }

    @GET
    @Path("/warehouses/{warehouseId}")
    @Tag(name = "Reference - Warehouses")
    @Operation(summary = "Get warehouse by ID",
               description = "Returns a specific warehouse by ID")
    public Uni<Map<String, Object>> getWarehouseById(@PathParam("warehouseId") String warehouseId) {
        LOG.infof("Querying warehouse by ID: %s", warehouseId);

        return postgresQuery.getWarehouseById(warehouseId)
            .onItem().transform(warehouse -> {
                if (warehouse == null) {
                    throw new NotFoundException("Warehouse not found: " + warehouseId);
                }
                return Map.of(
                    "warehouse", warehouse,
                    "timestamp", System.currentTimeMillis(),
                    "source", "postgresql"
                );
            });
    }

    @GET
    @Path("/warehouses/search")
    @Tag(name = "Reference - Warehouses")
    @Operation(summary = "Search warehouses by city",
               description = "Returns warehouses matching the city search term")
    public Uni<Map<String, Object>> searchWarehousesByCity(@QueryParam("city") String city) {
        LOG.infof("Searching warehouses by city: %s", city);

        if (city == null || city.isBlank()) {
            return postgresQuery.getAllWarehouses()
                .onItem().transform(warehouses -> Map.of(
                    "warehouses", warehouses,
                    "count", warehouses.size(),
                    "timestamp", System.currentTimeMillis(),
                    "source", "postgresql"
                ));
        }

        return postgresQuery.findWarehousesByCity(city)
            .onItem().transform(warehouses -> Map.of(
                "warehouses", warehouses,
                "count", warehouses.size(),
                "search_term", city,
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }

    // ===========================================
    // Customer Endpoints
    // ===========================================

    @GET
    @Path("/customers")
    @Tag(name = "Reference - Customers", description = "Query customer reference data")
    @Operation(summary = "Get customers",
               description = "Returns customers with pagination")
    public Uni<Map<String, Object>> getCustomers(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        LOG.infof("Querying customers with limit=%d, offset=%d", limit, offset);

        return postgresQuery.getCustomers(limit, offset)
            .onItem().transform(customers -> Map.of(
                "customers", customers,
                "count", customers.size(),
                "limit", limit,
                "offset", offset,
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }

    @GET
    @Path("/customers/{customerId}")
    @Tag(name = "Reference - Customers")
    @Operation(summary = "Get customer by ID",
               description = "Returns a specific customer by ID")
    public Uni<Map<String, Object>> getCustomerById(@PathParam("customerId") String customerId) {
        LOG.infof("Querying customer by ID: %s", customerId);

        return postgresQuery.getCustomerById(customerId)
            .onItem().transform(customer -> {
                if (customer == null) {
                    throw new NotFoundException("Customer not found: " + customerId);
                }
                return Map.of(
                    "customer", customer,
                    "timestamp", System.currentTimeMillis(),
                    "source", "postgresql"
                );
            });
    }

    @GET
    @Path("/customers/search")
    @Tag(name = "Reference - Customers")
    @Operation(summary = "Search customers by company name",
               description = "Returns customers matching the company name search term")
    public Uni<Map<String, Object>> searchCustomersByCompanyName(@QueryParam("name") String name) {
        LOG.infof("Searching customers by company name: %s", name);

        if (name == null || name.isBlank()) {
            return postgresQuery.getCustomers(50, 0)
                .onItem().transform(customers -> Map.of(
                    "customers", customers,
                    "count", customers.size(),
                    "timestamp", System.currentTimeMillis(),
                    "source", "postgresql"
                ));
        }

        return postgresQuery.findCustomersByCompanyName(name)
            .onItem().transform(customers -> Map.of(
                "customers", customers,
                "count", customers.size(),
                "search_term", name,
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }

    @GET
    @Path("/customers/by-sla/{slaTier}")
    @Tag(name = "Reference - Customers")
    @Operation(summary = "Get customers by SLA tier",
               description = "Returns customers for a specific SLA tier (STANDARD, EXPRESS, SAME_DAY, CRITICAL)")
    public Uni<Map<String, Object>> getCustomersBySLATier(@PathParam("slaTier") String slaTier) {
        LOG.infof("Querying customers by SLA tier: %s", slaTier);

        return postgresQuery.findCustomersBySLATier(slaTier)
            .onItem().transform(customers -> Map.of(
                "customers", customers,
                "count", customers.size(),
                "sla_tier", slaTier.toUpperCase(),
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }

    // ===========================================
    // Vehicle Endpoints
    // ===========================================

    @GET
    @Path("/vehicles")
    @Tag(name = "Reference - Vehicles", description = "Query vehicle reference data")
    @Operation(summary = "Get all vehicles",
               description = "Returns all vehicle reference data")
    public Uni<Map<String, Object>> getAllVehicles() {
        LOG.info("Querying all vehicles");

        return postgresQuery.getAllVehicles()
            .onItem().transform(vehicles -> Map.of(
                "vehicles", vehicles,
                "count", vehicles.size(),
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }

    @GET
    @Path("/vehicles/{vehicleId}")
    @Tag(name = "Reference - Vehicles")
    @Operation(summary = "Get vehicle by ID",
               description = "Returns a specific vehicle by ID")
    public Uni<Map<String, Object>> getVehicleById(@PathParam("vehicleId") String vehicleId) {
        LOG.infof("Querying vehicle by ID: %s", vehicleId);

        return postgresQuery.getVehicleById(vehicleId)
            .onItem().transform(vehicle -> {
                if (vehicle == null) {
                    throw new NotFoundException("Vehicle not found: " + vehicleId);
                }
                return Map.of(
                    "vehicle", vehicle,
                    "timestamp", System.currentTimeMillis(),
                    "source", "postgresql"
                );
            });
    }

    @GET
    @Path("/vehicles/by-warehouse/{warehouseId}")
    @Tag(name = "Reference - Vehicles")
    @Operation(summary = "Get vehicles by home warehouse",
               description = "Returns vehicles assigned to a specific warehouse")
    public Uni<Map<String, Object>> getVehiclesByWarehouse(@PathParam("warehouseId") String warehouseId) {
        LOG.infof("Querying vehicles by home warehouse: %s", warehouseId);

        return postgresQuery.findVehiclesByHomeWarehouse(warehouseId)
            .onItem().transform(vehicles -> Map.of(
                "vehicles", vehicles,
                "count", vehicles.size(),
                "warehouse_id", warehouseId,
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }

    // ===========================================
    // Driver Endpoints
    // ===========================================

    @GET
    @Path("/drivers")
    @Tag(name = "Reference - Drivers", description = "Query driver reference data")
    @Operation(summary = "Get all drivers",
               description = "Returns all driver reference data")
    public Uni<Map<String, Object>> getAllDrivers() {
        LOG.info("Querying all drivers");

        return postgresQuery.getAllDrivers()
            .onItem().transform(drivers -> Map.of(
                "drivers", drivers,
                "count", drivers.size(),
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }

    @GET
    @Path("/drivers/{driverId}")
    @Tag(name = "Reference - Drivers")
    @Operation(summary = "Get driver by ID",
               description = "Returns a specific driver by ID")
    public Uni<Map<String, Object>> getDriverById(@PathParam("driverId") String driverId) {
        LOG.infof("Querying driver by ID: %s", driverId);

        return postgresQuery.getDriverById(driverId)
            .onItem().transform(driver -> {
                if (driver == null) {
                    throw new NotFoundException("Driver not found: " + driverId);
                }
                return Map.of(
                    "driver", driver,
                    "timestamp", System.currentTimeMillis(),
                    "source", "postgresql"
                );
            });
    }

    @GET
    @Path("/drivers/available")
    @Tag(name = "Reference - Drivers")
    @Operation(summary = "Get available drivers",
               description = "Returns drivers with AVAILABLE status")
    public Uni<Map<String, Object>> getAvailableDrivers() {
        LOG.info("Querying available drivers");

        return postgresQuery.findAvailableDrivers()
            .onItem().transform(drivers -> Map.of(
                "drivers", drivers,
                "count", drivers.size(),
                "status", "AVAILABLE",
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }

    @GET
    @Path("/drivers/by-warehouse/{warehouseId}")
    @Tag(name = "Reference - Drivers")
    @Operation(summary = "Get drivers by home warehouse",
               description = "Returns drivers assigned to a specific warehouse")
    public Uni<Map<String, Object>> getDriversByWarehouse(@PathParam("warehouseId") String warehouseId) {
        LOG.infof("Querying drivers by home warehouse: %s", warehouseId);

        return postgresQuery.findDriversByHomeWarehouse(warehouseId)
            .onItem().transform(drivers -> Map.of(
                "drivers", drivers,
                "count", drivers.size(),
                "warehouse_id", warehouseId,
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }

    // ===========================================
    // Product Endpoints
    // ===========================================

    @GET
    @Path("/products")
    @Tag(name = "Reference - Products", description = "Query product reference data")
    @Operation(summary = "Search products",
               description = "Search products by category and/or search term")
    public Uni<Map<String, Object>> searchProducts(
            @QueryParam("category") String category,
            @QueryParam("search") String search,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        LOG.infof("Searching products: category=%s, search=%s, limit=%d, offset=%d", category, search, limit, offset);

        if (category != null && !category.isBlank()) {
            return postgresQuery.getProductsByCategory(category, limit, offset)
                .onItem().transform(products -> Map.of(
                    "products", products,
                    "count", products.size(),
                    "category", category.toUpperCase(),
                    "limit", limit,
                    "offset", offset,
                    "timestamp", System.currentTimeMillis(),
                    "source", "postgresql"
                ));
        } else if (search != null && !search.isBlank()) {
            return postgresQuery.searchProducts(search, limit)
                .onItem().transform(products -> Map.of(
                    "products", products,
                    "count", products.size(),
                    "search_term", search,
                    "limit", limit,
                    "timestamp", System.currentTimeMillis(),
                    "source", "postgresql"
                ));
        } else {
            // Default: return first page of all products
            return postgresQuery.searchProducts("", limit)
                .onItem().transform(products -> Map.of(
                    "products", products,
                    "count", products.size(),
                    "limit", limit,
                    "timestamp", System.currentTimeMillis(),
                    "source", "postgresql"
                ));
        }
    }

    // ===========================================
    // Route Endpoints
    // ===========================================

    @GET
    @Path("/routes")
    @Tag(name = "Reference - Routes", description = "Query route reference data")
    @Operation(summary = "Get all routes",
               description = "Returns all route reference data")
    public Uni<Map<String, Object>> getAllRoutes() {
        LOG.info("Querying all routes");

        return postgresQuery.getAllRoutes()
            .onItem().transform(routes -> Map.of(
                "routes", routes,
                "count", routes.size(),
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }

    @GET
    @Path("/routes/from/{warehouseId}")
    @Tag(name = "Reference - Routes")
    @Operation(summary = "Get routes from warehouse",
               description = "Returns routes originating from a specific warehouse")
    public Uni<Map<String, Object>> getRoutesFromWarehouse(@PathParam("warehouseId") String warehouseId) {
        LOG.infof("Querying routes from warehouse: %s", warehouseId);

        return postgresQuery.findRoutesByOrigin(warehouseId)
            .onItem().transform(routes -> Map.of(
                "routes", routes,
                "count", routes.size(),
                "origin_warehouse_id", warehouseId,
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }

    @GET
    @Path("/routes/to/{city}")
    @Tag(name = "Reference - Routes")
    @Operation(summary = "Get routes to city",
               description = "Returns routes to a specific destination city")
    public Uni<Map<String, Object>> getRoutesToCity(@PathParam("city") String city) {
        LOG.infof("Querying routes to city: %s", city);

        return postgresQuery.findRoutesByDestination(city)
            .onItem().transform(routes -> Map.of(
                "routes", routes,
                "count", routes.size(),
                "destination_city", city,
                "timestamp", System.currentTimeMillis(),
                "source", "postgresql"
            ));
    }
}
