package com.smartship.streams;

import com.smartship.common.ApicurioConfig;
import com.smartship.logistics.events.ShipmentEvent;
import com.smartship.logistics.events.ShipmentEventType;
import com.smartship.logistics.events.VehicleTelemetry;
import com.smartship.logistics.events.WarehouseOperation;
import com.smartship.logistics.events.OrderStatus;
import com.smartship.logistics.events.OrderStatusType;
import com.smartship.streams.model.*;
import com.smartship.streams.serde.JsonSerde;
import io.apicurio.registry.serde.avro.AvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Kafka Streams topology for SmartShip logistics.
 * Phase 3: All 6 state stores consuming 4 topics.
 */
public class LogisticsTopology {

    private static final Logger LOG = LoggerFactory.getLogger(LogisticsTopology.class);

    // Topic names
    private static final String SHIPMENT_EVENTS_TOPIC = "shipment.events";
    private static final String VEHICLE_TELEMETRY_TOPIC = "vehicle.telemetry";
    private static final String WAREHOUSE_OPERATIONS_TOPIC = "warehouse.operations";
    private static final String ORDER_STATUS_TOPIC = "order.status";

    // State store names - Shipment/Vehicle/Warehouse
    public static final String ACTIVE_SHIPMENTS_BY_STATUS_STORE = "active-shipments-by-status";
    public static final String VEHICLE_STATE_STORE = "vehicle-current-state";
    public static final String SHIPMENTS_BY_CUSTOMER_STORE = "shipments-by-customer";
    public static final String WAREHOUSE_METRICS_STORE = "warehouse-realtime-metrics";
    public static final String LATE_SHIPMENTS_STORE = "late-shipments";
    public static final String HOURLY_PERFORMANCE_STORE = "hourly-delivery-performance";

    // State store names - Orders (Phase 4)
    public static final String ORDER_STATE_STORE = "order-current-state";
    public static final String ORDERS_BY_CUSTOMER_STORE = "orders-by-customer";
    public static final String ORDER_SLA_TRACKING_STORE = "order-sla-tracking";

    // Backward compatibility alias
    public static final String STATE_STORE_NAME = ACTIVE_SHIPMENTS_BY_STATUS_STORE;

    // Window configurations
    private static final Duration WAREHOUSE_WINDOW_SIZE = Duration.ofMinutes(15);
    private static final Duration PERFORMANCE_WINDOW_SIZE = Duration.ofHours(1);
    private static final Duration PERFORMANCE_WINDOW_ADVANCE = Duration.ofMinutes(30);
    private static final Duration PERFORMANCE_WINDOW_GRACE = Duration.ofMinutes(5);
    private static final Duration WINDOW_RETENTION = Duration.ofHours(6);

    /**
     * Build the Kafka Streams topology.
     *
     * @return Configured topology
     */
    public static Topology build() {
        LOG.info("Building Kafka Streams topology for Phase 4 - 9 state stores");

        StreamsBuilder builder = new StreamsBuilder();

        // Configure Avro Serdes
        AvroSerde<ShipmentEvent> shipmentEventSerde = createAvroSerde();
        AvroSerde<VehicleTelemetry> vehicleTelemetrySerde = createAvroSerde();
        AvroSerde<WarehouseOperation> warehouseOperationSerde = createAvroSerde();
        AvroSerde<OrderStatus> orderStatusSerde = createAvroSerde();

        // Configure JSON Serdes for state store values
        JsonSerde<VehicleState> vehicleStateSerde = new JsonSerde<>(VehicleState.class);
        JsonSerde<CustomerShipmentStats> customerStatsSerde = new JsonSerde<>(CustomerShipmentStats.class);
        JsonSerde<WarehouseMetrics> warehouseMetricsSerde = new JsonSerde<>(WarehouseMetrics.class);
        JsonSerde<LateShipmentDetails> lateShipmentSerde = new JsonSerde<>(LateShipmentDetails.class);
        JsonSerde<DeliveryStats> deliveryStatsSerde = new JsonSerde<>(DeliveryStats.class);
        JsonSerde<OrderState> orderStateSerde = new JsonSerde<>(OrderState.class);
        JsonSerde<CustomerOrderStats> customerOrderStatsSerde = new JsonSerde<>(CustomerOrderStats.class);
        JsonSerde<OrderSLATracker> orderSLATrackerSerde = new JsonSerde<>(OrderSLATracker.class);

        // ===========================================
        // Stream from shipment.events topic
        // ===========================================
        KStream<String, ShipmentEvent> shipmentStream = builder.stream(
            SHIPMENT_EVENTS_TOPIC,
            Consumed.with(Serdes.String(), shipmentEventSerde)
        );

        shipmentStream.peek((key, value) ->
            LOG.debug("Processing shipment event: {} - {}", key, value.getEventType())
        );

        // State Store 1: active-shipments-by-status (count by status)
        buildActiveShipmentsByStatusStore(shipmentStream, shipmentEventSerde);

        // State Store 2: shipments-by-customer (aggregated stats per customer)
        buildShipmentsByCustomerStore(shipmentStream, shipmentEventSerde, customerStatsSerde);

        // State Store 3: late-shipments (track late shipments)
        buildLateShipmentsStore(shipmentStream, shipmentEventSerde, lateShipmentSerde);

        // State Store 4: hourly-delivery-performance (windowed delivery stats)
        buildHourlyPerformanceStore(shipmentStream, shipmentEventSerde, deliveryStatsSerde);

        // ===========================================
        // Stream from vehicle.telemetry topic
        // ===========================================
        KStream<String, VehicleTelemetry> vehicleStream = builder.stream(
            VEHICLE_TELEMETRY_TOPIC,
            Consumed.with(Serdes.String(), vehicleTelemetrySerde)
        );

        vehicleStream.peek((key, value) ->
            LOG.debug("Processing vehicle telemetry: {} - {}", key, value.getStatus())
        );

        // State Store 5: vehicle-current-state (latest telemetry per vehicle)
        buildVehicleStateStore(vehicleStream, vehicleTelemetrySerde, vehicleStateSerde);

        // ===========================================
        // Stream from warehouse.operations topic
        // ===========================================
        KStream<String, WarehouseOperation> warehouseStream = builder.stream(
            WAREHOUSE_OPERATIONS_TOPIC,
            Consumed.with(Serdes.String(), warehouseOperationSerde)
        );

        warehouseStream.peek((key, value) ->
            LOG.debug("Processing warehouse operation: {} - {}", key, value.getOperationType())
        );

        // State Store 6: warehouse-realtime-metrics (windowed metrics)
        buildWarehouseMetricsStore(warehouseStream, warehouseOperationSerde, warehouseMetricsSerde);

        // ===========================================
        // Stream from order.status topic (Phase 4)
        // ===========================================
        KStream<String, OrderStatus> orderStream = builder.stream(
            ORDER_STATUS_TOPIC,
            Consumed.with(Serdes.String(), orderStatusSerde)
        );

        orderStream.peek((key, value) ->
            LOG.debug("Processing order status: {} - {}", key, value.getStatus())
        );

        // State Store 7: order-current-state (latest state per order)
        buildOrderStateStore(orderStream, orderStatusSerde, orderStateSerde);

        // State Store 8: orders-by-customer (aggregated stats per customer)
        buildOrdersByCustomerStore(orderStream, orderStatusSerde, customerOrderStatsSerde);

        // State Store 9: order-sla-tracking (orders at SLA risk)
        buildOrderSLATrackingStore(orderStream, orderStatusSerde, orderSLATrackerSerde);

        Topology topology = builder.build();
        LOG.info("Topology built successfully with 9 state stores");
        LOG.info("Topology description:\n{}", topology.describe());

        return topology;
    }

    /**
     * State Store 1: Count shipments by status.
     */
    private static void buildActiveShipmentsByStatusStore(
            KStream<String, ShipmentEvent> shipmentStream,
            AvroSerde<ShipmentEvent> shipmentEventSerde) {

        LOG.info("Building state store: {}", ACTIVE_SHIPMENTS_BY_STATUS_STORE);

        KTable<String, Long> shipmentCountsByStatus = shipmentStream
            .groupBy(
                (key, value) -> value.getEventType().toString(),
                Grouped.with(Serdes.String(), shipmentEventSerde)
            )
            .count(
                Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as(ACTIVE_SHIPMENTS_BY_STATUS_STORE)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(Serdes.Long())
            );

        shipmentCountsByStatus.toStream().peek((status, count) ->
            LOG.debug("Updated {} count: {}", status, count)
        );
    }

    /**
     * State Store 2: Aggregated shipment stats per customer.
     */
    private static void buildShipmentsByCustomerStore(
            KStream<String, ShipmentEvent> shipmentStream,
            AvroSerde<ShipmentEvent> shipmentEventSerde,
            JsonSerde<CustomerShipmentStats> customerStatsSerde) {

        LOG.info("Building state store: {}", SHIPMENTS_BY_CUSTOMER_STORE);

        KTable<String, CustomerShipmentStats> customerStats = shipmentStream
            .groupBy(
                (key, value) -> value.getCustomerId(),
                Grouped.with(Serdes.String(), shipmentEventSerde)
            )
            .aggregate(
                CustomerShipmentStats::empty,
                (customerId, event, stats) -> stats.update(event),
                Materialized.<String, CustomerShipmentStats, KeyValueStore<Bytes, byte[]>>as(SHIPMENTS_BY_CUSTOMER_STORE)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(customerStatsSerde)
            );

        customerStats.toStream().peek((customerId, stats) ->
            LOG.debug("Updated customer {} stats: {} shipments", customerId, stats.totalShipments())
        );
    }

    /**
     * State Store 3: Track late shipments (30-min grace period).
     */
    private static void buildLateShipmentsStore(
            KStream<String, ShipmentEvent> shipmentStream,
            AvroSerde<ShipmentEvent> shipmentEventSerde,
            JsonSerde<LateShipmentDetails> lateShipmentSerde) {

        LOG.info("Building state store: {}", LATE_SHIPMENTS_STORE);

        // Filter for potentially late shipments and track them
        KTable<String, LateShipmentDetails> lateShipments = shipmentStream
            .filter((key, value) -> {
                // Filter out delivered/cancelled - they can't be late anymore
                ShipmentEventType type = value.getEventType();
                if (type == ShipmentEventType.DELIVERED || type == ShipmentEventType.CANCELLED) {
                    return false;
                }
                // Check if shipment is late
                return LateShipmentDetails.isLate(value);
            })
            .groupByKey(Grouped.with(Serdes.String(), shipmentEventSerde))
            .aggregate(
                () -> null,
                (shipmentId, event, current) -> LateShipmentDetails.from(event),
                Materialized.<String, LateShipmentDetails, KeyValueStore<Bytes, byte[]>>as(LATE_SHIPMENTS_STORE)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(lateShipmentSerde)
            );

        lateShipments.toStream().peek((shipmentId, details) -> {
            if (details != null) {
                LOG.debug("Late shipment {}: {} minutes delay", shipmentId, details.delayMinutes());
            }
        });
    }

    /**
     * State Store 4: Hourly delivery performance stats (1-hour hopping window).
     */
    private static void buildHourlyPerformanceStore(
            KStream<String, ShipmentEvent> shipmentStream,
            AvroSerde<ShipmentEvent> shipmentEventSerde,
            JsonSerde<DeliveryStats> deliveryStatsSerde) {

        LOG.info("Building state store: {}", HOURLY_PERFORMANCE_STORE);

        // Filter for DELIVERED events only
        TimeWindowedKStream<String, ShipmentEvent> windowedDeliveries = shipmentStream
            .filter((key, value) -> value.getEventType() == ShipmentEventType.DELIVERED)
            .groupBy(
                (key, value) -> value.getWarehouseId(),
                Grouped.with(Serdes.String(), shipmentEventSerde)
            )
            .windowedBy(
                TimeWindows.ofSizeAndGrace(PERFORMANCE_WINDOW_SIZE, PERFORMANCE_WINDOW_GRACE)
                    .advanceBy(PERFORMANCE_WINDOW_ADVANCE)
            );

        KTable<Windowed<String>, DeliveryStats> deliveryPerformance = windowedDeliveries
            .aggregate(
                DeliveryStats::empty,
                (warehouseId, event, stats) -> stats.recordDelivery(event),
                Materialized.<String, DeliveryStats, WindowStore<Bytes, byte[]>>as(HOURLY_PERFORMANCE_STORE)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(deliveryStatsSerde)
                    .withRetention(WINDOW_RETENTION)
            );

        deliveryPerformance.toStream().peek((windowedKey, stats) ->
            LOG.debug("Delivery performance for {} in window [{}-{}]: {} deliveries, {}% on-time",
                windowedKey.key(),
                windowedKey.window().start(),
                windowedKey.window().end(),
                stats.totalDelivered(),
                String.format("%.1f", stats.onTimePercentage()))
        );
    }

    /**
     * State Store 5: Latest vehicle state from telemetry.
     */
    private static void buildVehicleStateStore(
            KStream<String, VehicleTelemetry> vehicleStream,
            AvroSerde<VehicleTelemetry> vehicleTelemetrySerde,
            JsonSerde<VehicleState> vehicleStateSerde) {

        LOG.info("Building state store: {}", VEHICLE_STATE_STORE);

        // Key is already vehicle_id from the topic
        KTable<String, VehicleState> vehicleStates = vehicleStream
            .groupByKey(Grouped.with(Serdes.String(), vehicleTelemetrySerde))
            .aggregate(
                () -> null,
                (vehicleId, telemetry, current) -> VehicleState.from(telemetry),
                Materialized.<String, VehicleState, KeyValueStore<Bytes, byte[]>>as(VEHICLE_STATE_STORE)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(vehicleStateSerde)
            );

        vehicleStates.toStream().peek((vehicleId, state) -> {
            if (state != null) {
                LOG.debug("Updated vehicle {} state: {} at ({}, {})",
                    vehicleId, state.status(), state.latitude(), state.longitude());
            }
        });
    }

    /**
     * State Store 6: Real-time warehouse metrics (15-min tumbling window).
     */
    private static void buildWarehouseMetricsStore(
            KStream<String, WarehouseOperation> warehouseStream,
            AvroSerde<WarehouseOperation> warehouseOperationSerde,
            JsonSerde<WarehouseMetrics> warehouseMetricsSerde) {

        LOG.info("Building state store: {}", WAREHOUSE_METRICS_STORE);

        TimeWindowedKStream<String, WarehouseOperation> windowedOperations = warehouseStream
            .groupBy(
                (key, value) -> value.getWarehouseId(),
                Grouped.with(Serdes.String(), warehouseOperationSerde)
            )
            .windowedBy(
                TimeWindows.ofSizeWithNoGrace(WAREHOUSE_WINDOW_SIZE)
            );

        KTable<Windowed<String>, WarehouseMetrics> warehouseMetrics = windowedOperations
            .aggregate(
                WarehouseMetrics::empty,
                (warehouseId, operation, metrics) -> metrics.update(operation),
                Materialized.<String, WarehouseMetrics, WindowStore<Bytes, byte[]>>as(WAREHOUSE_METRICS_STORE)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(warehouseMetricsSerde)
                    .withRetention(WINDOW_RETENTION)
            );

        warehouseMetrics.toStream().peek((windowedKey, metrics) ->
            LOG.debug("Warehouse {} metrics in window [{}-{}]: {} operations, {} errors",
                windowedKey.key(),
                windowedKey.window().start(),
                windowedKey.window().end(),
                metrics.totalOperations(),
                metrics.errorCount())
        );
    }

    // ===========================================
    // Order State Stores (Phase 4)
    // ===========================================

    /**
     * State Store 7: Latest state per order.
     */
    private static void buildOrderStateStore(
            KStream<String, OrderStatus> orderStream,
            AvroSerde<OrderStatus> orderStatusSerde,
            JsonSerde<OrderState> orderStateSerde) {

        LOG.info("Building state store: {}", ORDER_STATE_STORE);

        KTable<String, OrderState> orderStates = orderStream
            .groupByKey(Grouped.with(Serdes.String(), orderStatusSerde))
            .aggregate(
                () -> null,
                (orderId, event, current) -> {
                    if (current == null) {
                        return OrderState.from(event);
                    }
                    return current.update(event);
                },
                Materialized.<String, OrderState, KeyValueStore<Bytes, byte[]>>as(ORDER_STATE_STORE)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(orderStateSerde)
            );

        orderStates.toStream().peek((orderId, state) -> {
            if (state != null) {
                LOG.debug("Updated order {} state: {}", orderId, state.status());
            }
        });
    }

    /**
     * State Store 8: Aggregated order statistics per customer.
     */
    private static void buildOrdersByCustomerStore(
            KStream<String, OrderStatus> orderStream,
            AvroSerde<OrderStatus> orderStatusSerde,
            JsonSerde<CustomerOrderStats> customerOrderStatsSerde) {

        LOG.info("Building state store: {}", ORDERS_BY_CUSTOMER_STORE);

        KTable<String, CustomerOrderStats> customerStats = orderStream
            .groupBy(
                (key, value) -> value.getCustomerId(),
                Grouped.with(Serdes.String(), orderStatusSerde)
            )
            .aggregate(
                CustomerOrderStats::empty,
                (customerId, event, stats) -> stats.update(event),
                Materialized.<String, CustomerOrderStats, KeyValueStore<Bytes, byte[]>>as(ORDERS_BY_CUSTOMER_STORE)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(customerOrderStatsSerde)
            );

        customerStats.toStream().peek((customerId, stats) ->
            LOG.debug("Updated customer {} order stats: {} total orders", customerId, stats.totalOrders())
        );
    }

    /**
     * State Store 9: Track orders at risk of SLA breach.
     */
    private static void buildOrderSLATrackingStore(
            KStream<String, OrderStatus> orderStream,
            AvroSerde<OrderStatus> orderStatusSerde,
            JsonSerde<OrderSLATracker> orderSLATrackerSerde) {

        LOG.info("Building state store: {}", ORDER_SLA_TRACKING_STORE);

        // Filter for orders that should be tracked (at risk or breached)
        KTable<String, OrderSLATracker> slaTracking = orderStream
            .filter((key, value) -> OrderSLATracker.shouldTrack(value))
            .groupByKey(Grouped.with(Serdes.String(), orderStatusSerde))
            .aggregate(
                () -> null,
                (orderId, event, current) -> OrderSLATracker.from(event),
                Materialized.<String, OrderSLATracker, KeyValueStore<Bytes, byte[]>>as(ORDER_SLA_TRACKING_STORE)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(orderSLATrackerSerde)
            );

        slaTracking.toStream().peek((orderId, tracker) -> {
            if (tracker != null) {
                LOG.debug("Order {} SLA tracking: {} minutes to SLA, at_risk={}, breached={}",
                    orderId, tracker.timeToSlaMinutes(), tracker.isAtRisk(), tracker.isBreached());
            }
        });
    }

    /**
     * Create an Avro Serde with Apicurio configuration.
     */
    private static <T> AvroSerde<T> createAvroSerde() {
        AvroSerde<T> serde = new AvroSerde<>();
        serde.configure(ApicurioConfig.createSerdeConfig(), false);
        return serde;
    }
}
