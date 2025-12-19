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
 * Order status generator for Phase 2.
 * Generates 10-15 events/second.
 * Creates orders with 1-3 shipments.
 * SLA tiers: STANDARD (5d), EXPRESS (2d), SAME_DAY (12h), CRITICAL (4h)
 */
public class OrderStatusGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(OrderStatusGenerator.class);
    private static final String TOPIC = "order.status";

    private static final int MIN_EVENTS_PER_SEC = 10;
    private static final int MAX_EVENTS_PER_SEC = 15;

    // SLA durations in milliseconds
    private static final Map<OrderPriority, Long> SLA_DURATIONS = Map.of(
        OrderPriority.STANDARD, 5L * 24 * 60 * 60 * 1000,    // 5 days
        OrderPriority.EXPRESS, 2L * 24 * 60 * 60 * 1000,     // 2 days
        OrderPriority.SAME_DAY, 12L * 60 * 60 * 1000,        // 12 hours
        OrderPriority.CRITICAL, 4L * 60 * 60 * 1000          // 4 hours
    );

    private final KafkaProducer<String, OrderStatus> producer;
    private final DataCorrelationManager correlationManager;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, OrderSimState> activeOrders;
    private final Random random = new Random();
    private final ShipmentEventGenerator shipmentGenerator;

    public OrderStatusGenerator(ShipmentEventGenerator shipmentGenerator) {
        this.shipmentGenerator = shipmentGenerator;
        this.producer = new KafkaProducer<>(KafkaConfig.createProducerConfig("order-status-generator"));
        this.correlationManager = DataCorrelationManager.getInstance();
        this.scheduler = Executors.newScheduledThreadPool(5);
        this.activeOrders = new ConcurrentHashMap<>();
        LOG.info("OrderStatusGenerator initialized with ShipmentEventGenerator coordination");
    }

    public void start() {
        LOG.info("Starting OrderStatusGenerator - target rate: {}-{} events/sec",
            MIN_EVENTS_PER_SEC, MAX_EVENTS_PER_SEC);

        // Generate new orders and status updates
        scheduler.scheduleAtFixedRate(() -> {
            try {
                int eventsThisSecond = MIN_EVENTS_PER_SEC + random.nextInt(MAX_EVENTS_PER_SEC - MIN_EVENTS_PER_SEC + 1);

                // Mix of new orders and status updates (1/3 new, 2/3 updates)
                int newOrders = eventsThisSecond / 3;
                int statusUpdates = eventsThisSecond - newOrders;

                for (int i = 0; i < newOrders; i++) {
                    createNewOrder();
                }

                for (int i = 0; i < statusUpdates; i++) {
                    updateExistingOrder();
                }
            } catch (Exception e) {
                LOG.error("Error generating order events", e);
            }
        }, 0, 1, TimeUnit.SECONDS);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down OrderStatusGenerator");
            scheduler.shutdown();
            producer.close();
        }));
    }

    private void createNewOrder() {
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String customerId = correlationManager.getRandomCustomerId();

        // Determine priority
        OrderPriority priority = selectPriority();

        // Calculate SLA timestamp
        long now = System.currentTimeMillis();
        long slaTimestamp = now + SLA_DURATIONS.get(priority);

        // Get a random warehouse and destination for the shipments
        String warehouseId = correlationManager.getRandomWarehouseId();
        DataCorrelationManager.DestinationInfo destination = correlationManager.getRandomDestination();

        // Generate 1-3 shipments per order - create ACTUAL shipments via ShipmentEventGenerator
        int shipmentCount = 1 + random.nextInt(3);
        List<String> shipmentIds = new ArrayList<>();
        for (int i = 0; i < shipmentCount; i++) {
            String shipmentId = "SH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            shipmentIds.add(shipmentId);

            // Create actual shipment via the shipment generator
            shipmentGenerator.createShipmentFromOrder(
                shipmentId,
                orderId,
                warehouseId,
                customerId,
                destination.getCity(),
                destination.getCountry(),
                slaTimestamp
            );
        }

        // Calculate total items
        int totalItems = shipmentCount * (1 + random.nextInt(10));

        // Create order state
        OrderSimState simState = new OrderSimState(orderId, customerId, shipmentIds, priority, slaTimestamp, totalItems);
        activeOrders.put(orderId, simState);

        // Register with correlation manager
        correlationManager.registerOrder(orderId, customerId, shipmentIds);

        // Send RECEIVED status
        sendOrderStatus(simState);

        LOG.debug("Created new order {} with {} actual shipments, priority: {}",
            orderId, shipmentCount, priority);
    }

    private void updateExistingOrder() {
        if (activeOrders.isEmpty()) {
            // No active orders, create a new one instead
            createNewOrder();
            return;
        }

        // Pick a random active order
        List<String> orderIds = new ArrayList<>(activeOrders.keySet());
        String orderId = orderIds.get(random.nextInt(orderIds.size()));
        OrderSimState simState = activeOrders.get(orderId);

        if (simState == null) {
            activeOrders.remove(orderId);
            return;
        }

        // Check if shipment coordination has signaled a ready status
        OrderStatusType readyStatus = correlationManager.getOrderReadyStatus(orderId);

        if (readyStatus != null) {
            // Advance to the ready status signaled by shipment coordination
            simState.currentStatus = readyStatus;
            correlationManager.clearOrderReadyStatus(orderId);
            sendOrderStatus(simState);
            correlationManager.updateOrderStatus(orderId, simState.currentStatus.name());

            if (simState.isTerminal()) {
                activeOrders.remove(orderId);
                correlationManager.removeOrder(orderId);
                LOG.info("Order {} reached terminal state: {}", orderId, readyStatus);
            }
            return;
        }

        // Only advance RECEIVED → VALIDATED → ALLOCATED on timer (no shipment dependency)
        // ALLOCATED → SHIPPED and SHIPPED → DELIVERED require shipment milestones
        if (simState.currentStatus == OrderStatusType.RECEIVED ||
            simState.currentStatus == OrderStatusType.VALIDATED) {

            if (!simState.advanceStatus()) {
                // Order was cancelled during early stages
                activeOrders.remove(orderId);
                correlationManager.removeOrder(orderId);
                return;
            }

            // Send updated status
            sendOrderStatus(simState);
            correlationManager.updateOrderStatus(orderId, simState.currentStatus.name());
        }
        // Orders in ALLOCATED or SHIPPED state wait for shipment milestones
        // They will be advanced when onShipmentStatusChanged signals readyStatus
    }

    private void sendOrderStatus(OrderSimState simState) {
        OrderStatus orderStatus = OrderStatus.newBuilder()
            .setOrderId(simState.orderId)
            .setCustomerId(simState.customerId)
            .setTimestamp(System.currentTimeMillis())
            .setStatus(simState.currentStatus)
            .setShipmentIds(simState.shipmentIds)
            .setTotalItems(simState.totalItems)
            .setSlaTimestamp(simState.slaTimestamp)
            .setPriority(simState.priority)
            .build();

        ProducerRecord<String, OrderStatus> record =
            new ProducerRecord<>(TOPIC, simState.orderId, orderStatus);

        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                LOG.error("Failed to send order status for {}", simState.orderId, exception);
            } else {
                LOG.debug("Sent order {} - status: {}", simState.orderId, simState.currentStatus);
            }
        });

        producer.flush();
    }

    private OrderPriority selectPriority() {
        // Distribution: 60% STANDARD, 25% EXPRESS, 10% SAME_DAY, 5% CRITICAL
        double rand = random.nextDouble();
        if (rand < 0.60) return OrderPriority.STANDARD;
        if (rand < 0.85) return OrderPriority.EXPRESS;
        if (rand < 0.95) return OrderPriority.SAME_DAY;
        return OrderPriority.CRITICAL;
    }

    /**
     * Internal simulation state for each order.
     */
    private static class OrderSimState {
        final String orderId;
        final String customerId;
        final List<String> shipmentIds;
        final OrderPriority priority;
        final long slaTimestamp;
        final int totalItems;
        OrderStatusType currentStatus;

        OrderSimState(String orderId, String customerId, List<String> shipmentIds,
                      OrderPriority priority, long slaTimestamp, int totalItems) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.shipmentIds = shipmentIds;
            this.priority = priority;
            this.slaTimestamp = slaTimestamp;
            this.totalItems = totalItems;
            this.currentStatus = OrderStatusType.RECEIVED;
        }

        boolean advanceStatus() {
            // Only handle early-stage timer-based transitions:
            // RECEIVED -> VALIDATED -> ALLOCATED
            // ALLOCATED -> SHIPPED and SHIPPED -> DELIVERED are handled by shipment coordination
            double rand = ThreadLocalRandom.current().nextDouble();

            switch (currentStatus) {
                case RECEIVED:
                    if (rand < 0.02) {
                        currentStatus = OrderStatusType.CANCELLED;
                    } else {
                        currentStatus = OrderStatusType.VALIDATED;
                    }
                    return true;

                case VALIDATED:
                    if (rand < 0.02) {
                        currentStatus = OrderStatusType.CANCELLED;
                    } else {
                        currentStatus = OrderStatusType.ALLOCATED;
                    }
                    return true;

                // ALLOCATED, SHIPPED wait for shipment coordination - don't advance on timer
                case ALLOCATED:
                case SHIPPED:
                    return true;  // Stay in current state, waiting for shipment milestones

                case DELIVERED:
                    if (rand < 0.02) {
                        currentStatus = OrderStatusType.RETURNED;
                        return true;
                    }
                    return false;  // Complete

                case CANCELLED:
                case RETURNED:
                case PARTIAL_FAILURE:
                    return false;  // Terminal states

                default:
                    return false;
            }
        }

        boolean isTerminal() {
            return currentStatus == OrderStatusType.DELIVERED ||
                   currentStatus == OrderStatusType.CANCELLED ||
                   currentStatus == OrderStatusType.RETURNED ||
                   currentStatus == OrderStatusType.PARTIAL_FAILURE;
        }
    }

    // Note: This generator must be started via GeneratorMain which provides the ShipmentEventGenerator dependency
}
