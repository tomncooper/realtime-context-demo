package com.smartship.generators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for all Phase 2 data generators.
 * Runs all generators in a single JVM for simplified deployment.
 */
public class GeneratorMain {

    private static final Logger LOG = LoggerFactory.getLogger(GeneratorMain.class);

    public static void main(String[] args) {
        LOG.info("=".repeat(60));
        LOG.info("SmartShip Logistics - Phase 2 Data Generators");
        LOG.info("=".repeat(60));

        // Initialize correlation manager first (singleton)
        LOG.info("\n=== Initializing DataCorrelationManager ===");
        DataCorrelationManager correlationManager = DataCorrelationManager.getInstance();
        LOG.info("DataCorrelationManager ready");

        // Start all generators
        LOG.info("\n=== Starting Shipment Event Generator ===");
        ShipmentEventGenerator shipmentGenerator = new ShipmentEventGenerator();
        shipmentGenerator.start();

        LOG.info("\n=== Starting Vehicle Telemetry Generator ===");
        VehicleTelemetryGenerator vehicleGenerator = new VehicleTelemetryGenerator();
        vehicleGenerator.start();

        LOG.info("\n=== Starting Warehouse Operation Generator ===");
        WarehouseOperationGenerator warehouseGenerator = new WarehouseOperationGenerator();
        warehouseGenerator.start();

        LOG.info("\n=== Starting Order Status Generator ===");
        OrderStatusGenerator orderGenerator = new OrderStatusGenerator();
        orderGenerator.start();

        LOG.info("\n" + "=".repeat(60));
        LOG.info("All generators started successfully!");
        LOG.info("Topics:");
        LOG.info("  - shipment.events (50-80 events/sec)");
        LOG.info("  - vehicle.telemetry (20-30 events/sec)");
        LOG.info("  - warehouse.operations (15-25 events/sec)");
        LOG.info("  - order.status (10-15 events/sec)");
        LOG.info("=".repeat(60));

        // Keep main thread alive
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("Generator main thread interrupted, shutting down...");
        }
    }
}
