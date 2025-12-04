package com.smartship.streams;

import com.smartship.common.KafkaConfig;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * Main application for Kafka Streams processor.
 * Processes shipment events and maintains materialized views.
 */
public class StreamsApplication {

    private static final Logger LOG = LoggerFactory.getLogger(StreamsApplication.class);
    private static final String APPLICATION_ID = "smartship-streams-processor";

    public static void main(String[] args) {
        LOG.info("=".repeat(60));
        LOG.info("SmartShip Logistics - Kafka Streams Processor");
        LOG.info("Phase 1: Active shipments by status");
        LOG.info("=".repeat(60));

        // Build topology
        Topology topology = LogisticsTopology.build();

        // Get application.server from environment variable for Interactive Queries
        String applicationServer = System.getenv("APPLICATION_SERVER");

        // Configure Streams
        Properties props = KafkaConfig.createStreamsConfig(APPLICATION_ID, applicationServer);
        LOG.info("Streams configuration:");
        LOG.info("  Application ID: {}", APPLICATION_ID);
        LOG.info("  Bootstrap Servers: {}", KafkaConfig.getBootstrapServers());
        LOG.info("  Apicurio Registry: {}", KafkaConfig.getApicurioRegistryUrl());
        if (applicationServer != null && !applicationServer.isEmpty()) {
            LOG.info("  Application Server: {}", applicationServer);
        } else {
            LOG.warn("  Application Server: NOT SET (Interactive Queries will not work for multi-instance deployments)");
        }

        // Create Streams instance
        final KafkaStreams streams = new KafkaStreams(topology, props);
        final CountDownLatch latch = new CountDownLatch(1);

        // Start Interactive Query Server
        final InteractiveQueryServer queryServer = new InteractiveQueryServer(streams);

        // Attach shutdown handler
        Runtime.getRuntime().addShutdownHook(new Thread("streams-shutdown-hook") {
            @Override
            public void run() {
                LOG.info("Shutting down Kafka Streams application");
                queryServer.stop();
                streams.close();
                latch.countDown();
                LOG.info("Kafka Streams application shutdown complete");
            }
        });

        try {
            // Start Kafka Streams
            LOG.info("Starting Kafka Streams...");
            streams.start();
            LOG.info("Kafka Streams started successfully");
            LOG.info("State: {}", streams.state());

            // Wait for streams to be in RUNNING state before starting query server
            while (streams.state() != KafkaStreams.State.RUNNING) {
                LOG.info("Waiting for streams to reach RUNNING state. Current state: {}", streams.state());
                Thread.sleep(1000);
            }

            // Start Interactive Query Server
            queryServer.start();

            // Keep application running
            latch.await();

        } catch (IOException e) {
            LOG.error("Failed to start Interactive Query Server", e);
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Application interrupted");
        } catch (Exception e) {
            LOG.error("Unexpected error", e);
            System.exit(1);
        }

        System.exit(0);
    }
}
