package com.smartship.generators;

import com.smartship.common.KafkaConfig;
import com.smartship.logistics.events.ShipmentEvent;
import com.smartship.logistics.events.ShipmentEventType;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simplified shipment event generator for Phase 1.
 * Generates complete shipment lifecycles: CREATED → IN_TRANSIT → DELIVERED
 */
public class ShipmentEventGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(ShipmentEventGenerator.class);
    private static final String TOPIC = "shipment.events";

    // European warehouses from PostgreSQL seed data
    private static final String[] WAREHOUSES = {
        "WH-RTM", // Rotterdam
        "WH-FRA", // Frankfurt
        "WH-BCN", // Barcelona
        "WH-WAW", // Warsaw
        "WH-STO"  // Stockholm
    };

    private final KafkaProducer<String, ShipmentEvent> producer;
    private final Random random = new Random();

    public ShipmentEventGenerator() {
        LOG.info("Initializing ShipmentEventGenerator");
        this.producer = new KafkaProducer<>(KafkaConfig.createProducerConfig("shipment-event-generator"));
        LOG.info("Connected to Kafka: {}", KafkaConfig.getBootstrapServers());
        LOG.info("Using Apicurio Registry: {}", KafkaConfig.getApicurioRegistryUrl());
    }

    /**
     * Start generating shipment lifecycle events.
     */
    public void start() {
        LOG.info("Starting ShipmentEventGenerator - Phase 1");
        LOG.info("Generating shipment lifecycles every 6 seconds");

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        // Generate a new shipment lifecycle every 6 seconds
        scheduler.scheduleAtFixedRate(() -> {
            try {
                generateShipmentLifecycle();
            } catch (Exception e) {
                LOG.error("Error generating shipment lifecycle", e);
            }
        }, 0, 6, TimeUnit.SECONDS);

        // Shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down ShipmentEventGenerator");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            producer.close();
            LOG.info("ShipmentEventGenerator shutdown complete");
        }));
    }

    /**
     * Generate a complete shipment lifecycle with realistic timing.
     */
    private void generateShipmentLifecycle() {
        // Generate unique shipment ID
        String shipmentId = "SH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Select random warehouse
        String warehouseId = WAREHOUSES[random.nextInt(WAREHOUSES.length)];

        LOG.info("Starting lifecycle for shipment: {} from warehouse: {}", shipmentId, warehouseId);

        try {
            // 1. CREATED
            sendEvent(shipmentId, warehouseId, ShipmentEventType.CREATED);
            Thread.sleep(2000); // 2 seconds delay

            // 2. IN_TRANSIT
            sendEvent(shipmentId, warehouseId, ShipmentEventType.IN_TRANSIT);
            Thread.sleep(2000); // 2 seconds delay

            // 3. DELIVERED
            sendEvent(shipmentId, warehouseId, ShipmentEventType.DELIVERED);

            LOG.info("Completed lifecycle for shipment: {}", shipmentId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Lifecycle generation interrupted for shipment: {}", shipmentId);
        }
    }

    /**
     * Send a single shipment event to Kafka.
     */
    private void sendEvent(String shipmentId, String warehouseId, ShipmentEventType eventType) {
        ShipmentEvent event = ShipmentEvent.newBuilder()
            .setShipmentId(shipmentId)
            .setWarehouseId(warehouseId)
            .setEventType(eventType)
            .setTimestamp(System.currentTimeMillis())
            .build();

        ProducerRecord<String, ShipmentEvent> record = new ProducerRecord<>(TOPIC, shipmentId, event);

        producer.send(record, (RecordMetadata metadata, Exception exception) -> {
            if (exception != null) {
                LOG.error("Failed to send event for shipment: {} - {}", shipmentId, eventType, exception);
            } else {
                LOG.debug("Sent: {} - {} to partition {} at offset {}",
                    shipmentId, eventType, metadata.partition(), metadata.offset());
            }
        });

        // Flush to ensure delivery
        producer.flush();
    }

    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        LOG.info("=".repeat(60));
        LOG.info("SmartShip Logistics - Shipment Event Generator");
        LOG.info("Phase 1: Simplified event generation");
        LOG.info("=".repeat(60));

        ShipmentEventGenerator generator = new ShipmentEventGenerator();
        generator.start();

        // Keep main thread alive
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("Generator interrupted");
        }
    }
}
