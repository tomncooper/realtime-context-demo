package com.smartship.generators;

import com.smartship.common.KafkaConfig;
import com.smartship.generators.DataCorrelationManager.DestinationInfo;
import com.smartship.logistics.events.ShipmentEvent;
import com.smartship.logistics.events.ShipmentEventType;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.*;

/**
 * Phase 2 shipment event generator with full lifecycle support.
 * Generates events at 50-80 events/second with realistic lifecycle progression.
 *
 * Lifecycle: CREATED -> PICKED -> PACKED -> DISPATCHED -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED
 * With 5% EXCEPTION rate and 2% CANCELLED rate.
 */
public class ShipmentEventGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(ShipmentEventGenerator.class);
    private static final String TOPIC = "shipment.events";

    // Target rate: 50-80 new shipments per second, but with staggered lifecycle
    private static final int NEW_SHIPMENTS_PER_SECOND = 10;

    // Exception and cancellation rates
    private static final double EXCEPTION_RATE = 0.05;
    private static final double CANCELLATION_RATE = 0.02;

    // Rate of shipments that will be generated as "already late"
    private static final double LATE_SHIPMENT_RATE = 0.05;

    // SLA durations in milliseconds
    private static final long SLA_STANDARD = 5L * 24 * 60 * 60 * 1000;  // 5 days
    private static final long SLA_EXPRESS = 2L * 24 * 60 * 60 * 1000;   // 2 days
    private static final long SLA_SAME_DAY = 12L * 60 * 60 * 1000;      // 12 hours
    private static final long SLA_CRITICAL = 4L * 60 * 60 * 1000;       // 4 hours

    private final KafkaProducer<String, ShipmentEvent> producer;
    private final DataCorrelationManager correlationManager;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, ShipmentSimState> activeLifecycles;

    public ShipmentEventGenerator() {
        LOG.info("Initializing ShipmentEventGenerator - Phase 2");
        this.producer = new KafkaProducer<>(KafkaConfig.createProducerConfig("shipment-event-generator"));
        this.correlationManager = DataCorrelationManager.getInstance();
        this.scheduler = Executors.newScheduledThreadPool(10);
        this.activeLifecycles = new ConcurrentHashMap<>();
        LOG.info("Connected to Kafka: {}", KafkaConfig.getBootstrapServers());
    }

    public void start() {
        LOG.info("Starting ShipmentEventGenerator - Phase 2");
        LOG.info("Generating {} new shipments per second", NEW_SHIPMENTS_PER_SECOND);

        // Schedule new shipment creation
        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (int i = 0; i < NEW_SHIPMENTS_PER_SECOND; i++) {
                    createNewShipment();
                }
            } catch (Exception e) {
                LOG.error("Error creating new shipments", e);
            }
        }, 0, 1, TimeUnit.SECONDS);

        // Schedule lifecycle progression (check every 500ms)
        scheduler.scheduleAtFixedRate(() -> {
            try {
                progressLifecycles();
            } catch (Exception e) {
                LOG.error("Error progressing lifecycles", e);
            }
        }, 500, 500, TimeUnit.MILLISECONDS);

        // Shutdown hook
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

    private void createNewShipment() {
        String shipmentId = "SH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String warehouseId = correlationManager.getRandomWarehouseId();
        String customerId = correlationManager.getRandomCustomerId();
        DestinationInfo destination = correlationManager.getRandomDestination();

        // Calculate expected delivery - some shipments are intentionally "late"
        long now = System.currentTimeMillis();
        long expectedDelivery;
        if (ThreadLocalRandom.current().nextDouble() < LATE_SHIPMENT_RATE) {
            // This shipment will be late (expected delivery in the past)
            expectedDelivery = now + selectLateOffset();
            LOG.debug("Creating late shipment with past expected delivery");
        } else {
            expectedDelivery = now + selectSlaOffset();
        }

        // Register with correlation manager
        correlationManager.registerShipment(shipmentId, warehouseId, customerId,
            destination.getCity(), destination.getCountry(), expectedDelivery);

        // Create simulation state
        ShipmentSimState simState = new ShipmentSimState(shipmentId, warehouseId, customerId,
            destination.getCity(), destination.getCountry(), expectedDelivery);
        activeLifecycles.put(shipmentId, simState);

        // Send CREATED event
        sendEvent(simState, ShipmentEventType.CREATED);

        LOG.debug("Created shipment: {} for customer: {} to {}",
            shipmentId, customerId, destination.getCity());
    }

    /**
     * Create a shipment from an order.
     * This method is called by OrderStatusGenerator to create actual shipments
     * that are linked to orders for status coordination.
     */
    public void createShipmentFromOrder(String shipmentId, String orderId, String warehouseId,
                                         String customerId, String destinationCity,
                                         String destinationCountry, long expectedDelivery) {
        // Register shipment-to-order mapping
        correlationManager.registerShipmentForOrder(shipmentId, orderId);

        // Register shipment state
        correlationManager.registerShipment(shipmentId, warehouseId, customerId,
            destinationCity, destinationCountry, expectedDelivery);

        // Create simulation state and add to active lifecycles
        ShipmentSimState simState = new ShipmentSimState(shipmentId, warehouseId, customerId,
            destinationCity, destinationCountry, expectedDelivery);
        activeLifecycles.put(shipmentId, simState);

        // Send CREATED event
        sendEvent(simState, ShipmentEventType.CREATED);

        // Notify coordination manager
        correlationManager.onShipmentStatusChanged(shipmentId, "CREATED");

        LOG.debug("Created order-linked shipment: {} for order: {} customer: {} to {}",
            shipmentId, orderId, customerId, destinationCity);
    }

    private void progressLifecycles() {
        long now = System.currentTimeMillis();

        for (ShipmentSimState simState : activeLifecycles.values()) {
            if (simState.isReadyForNextState(now)) {
                ShipmentEventType nextState = simState.getNextState();

                if (nextState == null) {
                    // Lifecycle complete
                    activeLifecycles.remove(simState.shipmentId);
                    correlationManager.removeShipment(simState.shipmentId);
                    correlationManager.removeShipmentOrderMapping(simState.shipmentId);
                    continue;
                }

                // Check for exception or cancellation
                double rand = ThreadLocalRandom.current().nextDouble();
                if (simState.currentState == ShipmentEventType.CREATED && rand < CANCELLATION_RATE) {
                    sendEvent(simState, ShipmentEventType.CANCELLED);
                    correlationManager.updateShipmentStatus(simState.shipmentId, "CANCELLED");
                    correlationManager.onShipmentStatusChanged(simState.shipmentId, "CANCELLED");
                    activeLifecycles.remove(simState.shipmentId);
                    correlationManager.removeShipment(simState.shipmentId);
                    correlationManager.removeShipmentOrderMapping(simState.shipmentId);
                    continue;
                }

                if (simState.currentState == ShipmentEventType.IN_TRANSIT && rand < EXCEPTION_RATE) {
                    sendEvent(simState, ShipmentEventType.EXCEPTION);
                    correlationManager.updateShipmentStatus(simState.shipmentId, "EXCEPTION");
                    correlationManager.onShipmentStatusChanged(simState.shipmentId, "EXCEPTION");
                    // Exception doesn't end lifecycle, just delays it
                    simState.scheduleNextTransition(now, 5000); // 5 second delay after exception
                    continue;
                }

                // Normal transition
                sendEvent(simState, nextState);
                simState.advanceState(nextState, now);

                // Update correlation manager and notify for order coordination
                correlationManager.updateShipmentStatus(simState.shipmentId, nextState.name());
                correlationManager.onShipmentStatusChanged(simState.shipmentId, nextState.name());
            }
        }
    }

    private void sendEvent(ShipmentSimState simState, ShipmentEventType eventType) {
        ShipmentEvent event = ShipmentEvent.newBuilder()
            .setShipmentId(simState.shipmentId)
            .setWarehouseId(simState.warehouseId)
            .setCustomerId(simState.customerId)
            .setExpectedDelivery(simState.expectedDelivery)
            .setDestinationCity(simState.destinationCity)
            .setDestinationCountry(simState.destinationCountry)
            .setEventType(eventType)
            .setTimestamp(System.currentTimeMillis())
            .build();

        ProducerRecord<String, ShipmentEvent> record = new ProducerRecord<>(TOPIC, simState.shipmentId, event);

        producer.send(record, (RecordMetadata metadata, Exception exception) -> {
            if (exception != null) {
                LOG.error("Failed to send event for shipment: {} - {}", simState.shipmentId, eventType, exception);
            } else {
                LOG.debug("Sent: {} - {} to partition {} at offset {}",
                    simState.shipmentId, eventType, metadata.partition(), metadata.offset());
            }
        });

        producer.flush();
    }

    private long selectSlaOffset() {
        double rand = ThreadLocalRandom.current().nextDouble();
        if (rand < 0.60) return SLA_STANDARD;
        if (rand < 0.85) return SLA_EXPRESS;
        if (rand < 0.95) return SLA_SAME_DAY;
        return SLA_CRITICAL;
    }

    /**
     * Calculate a past expected delivery time for simulating late shipments.
     * Returns a negative offset that places expected delivery 35-90 minutes in the past,
     * ensuring the shipment is immediately past the 30-minute grace period.
     */
    private long selectLateOffset() {
        long minOffset = 35L * 60 * 1000;  // 35 minutes
        long maxOffset = 90L * 60 * 1000;  // 90 minutes
        return -(minOffset + ThreadLocalRandom.current().nextLong(maxOffset - minOffset));
    }

    /**
     * Internal simulation state for each shipment lifecycle.
     */
    private static class ShipmentSimState {
        final String shipmentId;
        final String warehouseId;
        final String customerId;
        final String destinationCity;
        final String destinationCountry;
        final long expectedDelivery;
        ShipmentEventType currentState;
        long nextTransitionTime;

        ShipmentSimState(String shipmentId, String warehouseId, String customerId,
                        String destinationCity, String destinationCountry, long expectedDelivery) {
            this.shipmentId = shipmentId;
            this.warehouseId = warehouseId;
            this.customerId = customerId;
            this.destinationCity = destinationCity;
            this.destinationCountry = destinationCountry;
            this.expectedDelivery = expectedDelivery;
            this.currentState = ShipmentEventType.CREATED;
            this.nextTransitionTime = System.currentTimeMillis() + randomDelay(1000, 3000);
        }

        boolean isReadyForNextState(long now) {
            return now >= nextTransitionTime;
        }

        ShipmentEventType getNextState() {
            return switch (currentState) {
                case CREATED -> ShipmentEventType.PICKED;
                case PICKED -> ShipmentEventType.PACKED;
                case PACKED -> ShipmentEventType.DISPATCHED;
                case DISPATCHED -> ShipmentEventType.IN_TRANSIT;
                case IN_TRANSIT -> ShipmentEventType.OUT_FOR_DELIVERY;
                case OUT_FOR_DELIVERY -> ShipmentEventType.DELIVERED;
                case DELIVERED, CANCELLED, EXCEPTION -> null;
            };
        }

        void advanceState(ShipmentEventType newState, long now) {
            this.currentState = newState;
            // Different delays for different stages
            long delay = switch (newState) {
                case PICKED -> randomDelay(1000, 3000);     // 1-3 seconds (picking)
                case PACKED -> randomDelay(1000, 2000);     // 1-2 seconds (packing)
                case DISPATCHED -> randomDelay(500, 1500);  // 0.5-1.5 seconds (dispatch)
                case IN_TRANSIT -> randomDelay(3000, 8000); // 3-8 seconds (simulating transit)
                case OUT_FOR_DELIVERY -> randomDelay(2000, 5000); // 2-5 seconds (last mile)
                case DELIVERED -> 0;
                default -> randomDelay(1000, 2000);
            };
            this.nextTransitionTime = now + delay;
        }

        void scheduleNextTransition(long now, long delayMs) {
            this.nextTransitionTime = now + delayMs;
        }

        private static long randomDelay(long min, long max) {
            return min + ThreadLocalRandom.current().nextLong(max - min);
        }
    }

    public static void main(String[] args) {
        LOG.info("=".repeat(60));
        LOG.info("SmartShip Logistics - Shipment Event Generator");
        LOG.info("Phase 2: Full lifecycle event generation");
        LOG.info("=".repeat(60));

        ShipmentEventGenerator generator = new ShipmentEventGenerator();
        generator.start();

        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("Generator interrupted");
        }
    }
}
