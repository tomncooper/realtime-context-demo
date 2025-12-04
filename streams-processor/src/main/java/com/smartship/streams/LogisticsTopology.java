package com.smartship.streams;

import com.smartship.common.ApicurioConfig;
import com.smartship.logistics.events.ShipmentEvent;
import io.apicurio.registry.serde.avro.AvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka Streams topology for SmartShip logistics.
 * Phase 1: Single state store counting shipments by status.
 */
public class LogisticsTopology {

    private static final Logger LOG = LoggerFactory.getLogger(LogisticsTopology.class);

    private static final String SHIPMENT_EVENTS_TOPIC = "shipment.events";
    public static final String STATE_STORE_NAME = "active-shipments-by-status";

    /**
     * Build the Kafka Streams topology.
     *
     * @return Configured topology
     */
    public static Topology build() {
        LOG.info("Building Kafka Streams topology for Phase 1");

        StreamsBuilder builder = new StreamsBuilder();

        // Configure Avro Serde for ShipmentEvent
        AvroSerde<ShipmentEvent> shipmentEventSerde = new AvroSerde<>();
        shipmentEventSerde.configure(ApicurioConfig.createSerdeConfig(), false);

        // Stream from shipment events topic
        KStream<String, ShipmentEvent> shipmentStream = builder.stream(
            SHIPMENT_EVENTS_TOPIC,
            Consumed.with(Serdes.String(), shipmentEventSerde)
        );

        // Log incoming events
        shipmentStream.peek((key, value) ->
            LOG.debug("Processing event: {} - {}", key, value.getEventType())
        );

        // Group by event type (status) and count
        KTable<String, Long> shipmentCountsByStatus = shipmentStream
            .groupBy(
                (key, value) -> value.getEventType().toString(),
                Grouped.with(Serdes.String(), shipmentEventSerde)
            )
            .count(
                Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as(STATE_STORE_NAME)
                    .withKeySerde(Serdes.String())
                    .withValueSerde(Serdes.Long())
            );

        // Log aggregation updates
        shipmentCountsByStatus.toStream().peek((status, count) ->
            LOG.info("Updated count for status {}: {}", status, count)
        );

        Topology topology = builder.build();
        LOG.info("Topology built successfully");
        LOG.info("Topology description:\n{}", topology.describe());

        return topology;
    }
}
