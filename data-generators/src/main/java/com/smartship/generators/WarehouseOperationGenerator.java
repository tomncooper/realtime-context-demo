package com.smartship.generators;

import com.smartship.common.KafkaConfig;
import com.smartship.logistics.events.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Warehouse operation generator for Phase 2.
 * Generates 15-25 events/second across 5 warehouses.
 * Links PICK/PACK operations to active shipments.
 * 3% error rate.
 */
public class WarehouseOperationGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(WarehouseOperationGenerator.class);
    private static final String TOPIC = "warehouse.operations";

    private static final double ERROR_RATE = 0.03;  // 3% error rate
    private static final int MIN_EVENTS_PER_SEC = 15;
    private static final int MAX_EVENTS_PER_SEC = 25;

    private final KafkaProducer<String, WarehouseOperation> producer;
    private final DataCorrelationManager correlationManager;
    private final ScheduledExecutorService scheduler;
    private final Random random = new Random();

    // Error messages for various operation types
    private static final Map<OperationType, List<String>> ERROR_MESSAGES = Map.of(
        OperationType.RECEIVING, List.of("DAMAGED_GOODS", "QUANTITY_MISMATCH", "WRONG_PRODUCT"),
        OperationType.PUTAWAY, List.of("LOCATION_OCCUPIED", "WEIGHT_EXCEEDED", "SCANNER_FAILURE"),
        OperationType.PICK, List.of("ITEM_NOT_FOUND", "INSUFFICIENT_STOCK", "WRONG_LOCATION"),
        OperationType.PACK, List.of("PACKAGING_SHORTAGE", "WEIGHT_MISMATCH", "LABEL_PRINTER_ERROR"),
        OperationType.LOAD, List.of("VEHICLE_NOT_READY", "CAPACITY_EXCEEDED", "DOCK_UNAVAILABLE"),
        OperationType.INVENTORY_ADJUSTMENT, List.of("AUDIT_DISCREPANCY", "SYSTEM_ERROR"),
        OperationType.CYCLE_COUNT, List.of("COUNT_MISMATCH", "BARCODE_UNREADABLE")
    );

    public WarehouseOperationGenerator() {
        this.producer = new KafkaProducer<>(KafkaConfig.createProducerConfig("warehouse-operation-generator"));
        this.correlationManager = DataCorrelationManager.getInstance();
        this.scheduler = Executors.newScheduledThreadPool(5);
        LOG.info("WarehouseOperationGenerator initialized");
    }

    public void start() {
        LOG.info("Starting WarehouseOperationGenerator - target rate: {}-{} events/sec",
            MIN_EVENTS_PER_SEC, MAX_EVENTS_PER_SEC);

        // Schedule event generation at variable rate
        scheduler.scheduleAtFixedRate(() -> {
            try {
                int eventsThisSecond = MIN_EVENTS_PER_SEC + random.nextInt(MAX_EVENTS_PER_SEC - MIN_EVENTS_PER_SEC + 1);
                for (int i = 0; i < eventsThisSecond; i++) {
                    generateOperation();
                }
            } catch (Exception e) {
                LOG.error("Error generating warehouse operations", e);
            }
        }, 0, 1, TimeUnit.SECONDS);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down WarehouseOperationGenerator");
            scheduler.shutdown();
            producer.close();
        }));
    }

    private void generateOperation() {
        String eventId = "WOP-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String warehouseId = correlationManager.getRandomWarehouseId();

        // Select operation type with weighted distribution
        OperationType opType = selectOperationType();

        // Get shipment ID for PICK/PACK/LOAD operations
        String shipmentId = null;
        if (opType == OperationType.PICK || opType == OperationType.PACK || opType == OperationType.LOAD) {
            List<String> activeShipments = correlationManager.getActiveShipmentIdsForWarehouse(warehouseId);
            if (!activeShipments.isEmpty()) {
                shipmentId = activeShipments.get(random.nextInt(activeShipments.size()));
            }
        }

        // Generate errors based on error rate
        List<String> errors = new ArrayList<>();
        if (random.nextDouble() < ERROR_RATE) {
            List<String> possibleErrors = ERROR_MESSAGES.getOrDefault(opType, List.of("GENERAL_ERROR"));
            errors.add(possibleErrors.get(random.nextInt(possibleErrors.size())));
        }

        // Build warehouse operation event
        WarehouseOperation.Builder builder = WarehouseOperation.newBuilder()
            .setEventId(eventId)
            .setWarehouseId(warehouseId)
            .setTimestamp(System.currentTimeMillis())
            .setOperationType(opType)
            .setOperatorId("OP-" + String.format("%03d", 1 + random.nextInt(50)))
            .setProductId(correlationManager.getRandomProductId())
            .setQuantity(1 + random.nextInt(20))
            .setLocation(generateWarehouseLocation())
            .setDurationSeconds(10 + random.nextInt(300))  // 10 seconds to 5 minutes
            .setErrors(errors);

        if (shipmentId != null) {
            builder.setShipmentId(shipmentId);
        }

        WarehouseOperation operation = builder.build();

        // Send to Kafka
        ProducerRecord<String, WarehouseOperation> record =
            new ProducerRecord<>(TOPIC, warehouseId, operation);

        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                LOG.error("Failed to send warehouse operation {}", eventId, exception);
            } else {
                LOG.debug("Sent operation {} - type: {}, warehouse: {}",
                    eventId, opType, warehouseId);
            }
        });

        producer.flush();
    }

    private OperationType selectOperationType() {
        // Weighted distribution of operation types
        double rand = random.nextDouble();
        if (rand < 0.25) return OperationType.PICK;
        if (rand < 0.45) return OperationType.PACK;
        if (rand < 0.60) return OperationType.RECEIVING;
        if (rand < 0.75) return OperationType.PUTAWAY;
        if (rand < 0.85) return OperationType.LOAD;
        if (rand < 0.95) return OperationType.INVENTORY_ADJUSTMENT;
        return OperationType.CYCLE_COUNT;
    }

    private String generateWarehouseLocation() {
        // Format: AISLE-RACK-SHELF (e.g., A01-R05-S03)
        char aisle = (char) ('A' + random.nextInt(10));
        int aisleNum = random.nextInt(10);
        int rack = 1 + random.nextInt(20);
        int shelf = 1 + random.nextInt(8);
        return String.format("%c%02d-R%02d-S%02d", aisle, aisleNum, rack, shelf);
    }

    public static void main(String[] args) {
        LOG.info("Starting WarehouseOperationGenerator");
        WarehouseOperationGenerator generator = new WarehouseOperationGenerator();
        generator.start();

        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
