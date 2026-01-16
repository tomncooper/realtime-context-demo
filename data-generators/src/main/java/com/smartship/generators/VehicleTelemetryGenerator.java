package com.smartship.generators;

import com.smartship.common.KafkaConfig;
import com.smartship.generators.DataCorrelationManager.WarehouseLocation;
import com.smartship.logistics.events.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Vehicle telemetry generator.
 * Generates 20-30 events/second across 50 vehicles.
 * Each vehicle updates every 30-60 seconds.
 */
public class VehicleTelemetryGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(VehicleTelemetryGenerator.class);
    private static final String TOPIC = "vehicle.telemetry";

    // Status distribution: 60% EN_ROUTE, 25% IDLE, 10% LOADING/UNLOADING, 5% MAINTENANCE
    private static final double EN_ROUTE_PROBABILITY = 0.60;
    private static final double IDLE_PROBABILITY = 0.25;
    private static final double LOADING_PROBABILITY = 0.10;

    private final KafkaProducer<String, VehicleTelemetry> producer;
    private final DataCorrelationManager correlationManager;
    private final ConcurrentHashMap<String, VehicleSimState> vehicleStates;
    private final ScheduledExecutorService scheduler;

    public VehicleTelemetryGenerator() {
        this.producer = new KafkaProducer<>(KafkaConfig.createProducerConfig("vehicle-telemetry-generator"));
        this.correlationManager = DataCorrelationManager.getInstance();
        this.vehicleStates = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(10);

        initializeVehicleStates();
        LOG.info("VehicleTelemetryGenerator initialized with {} vehicles", vehicleStates.size());
    }

    private void initializeVehicleStates() {
        for (String vehicleId : correlationManager.getVehicleIds()) {
            VehicleSimState state = new VehicleSimState(vehicleId, correlationManager);
            vehicleStates.put(vehicleId, state);
        }
    }

    public void start() {
        LOG.info("Starting VehicleTelemetryGenerator");

        // Schedule each vehicle's telemetry updates with staggered starts
        int vehicleIndex = 0;
        for (String vehicleId : vehicleStates.keySet()) {
            // Stagger initial delays to spread out events
            long initialDelay = vehicleIndex * 600L; // 600ms apart
            // Update interval 1.5-2.5 seconds per vehicle (to achieve 20-30 events/sec across 50 vehicles)
            long interval = 1500 + ThreadLocalRandom.current().nextLong(1000);

            scheduler.scheduleAtFixedRate(() -> {
                try {
                    generateTelemetry(vehicleId);
                } catch (Exception e) {
                    LOG.error("Error generating telemetry for vehicle {}", vehicleId, e);
                }
            }, initialDelay, interval, TimeUnit.MILLISECONDS);

            vehicleIndex++;
        }

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down VehicleTelemetryGenerator");
            scheduler.shutdown();
            producer.close();
        }));
    }

    private void generateTelemetry(String vehicleId) {
        VehicleSimState simState = vehicleStates.get(vehicleId);
        if (simState == null) return;

        // Update simulation state
        simState.updateState();

        // Build telemetry event
        GeoLocation location = GeoLocation.newBuilder()
            .setLatitude(simState.latitude)
            .setLongitude(simState.longitude)
            .setSpeedKmh(simState.speedKmh)
            .setHeading(simState.heading)
            .build();

        VehicleLoad load = VehicleLoad.newBuilder()
            .setShipmentCount(simState.shipmentCount)
            .setWeightKg(simState.weightKg)
            .setVolumeCubicM(simState.volumeCubicM)
            .build();

        VehicleTelemetry telemetry = VehicleTelemetry.newBuilder()
            .setVehicleId(vehicleId)
            .setTimestamp(System.currentTimeMillis())
            .setLocation(location)
            .setStatus(simState.status)
            .setCurrentLoad(load)
            .setDriverId(simState.driverId)
            .setFuelLevelPercent(simState.fuelLevel)
            .setOdometerKm(simState.odometer)
            .build();

        // Send to Kafka
        ProducerRecord<String, VehicleTelemetry> record =
            new ProducerRecord<>(TOPIC, vehicleId, telemetry);

        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                LOG.error("Failed to send telemetry for vehicle {}", vehicleId, exception);
            } else {
                LOG.debug("Sent telemetry for {} - status: {}, speed: {:.1f} km/h",
                    vehicleId, simState.status, simState.speedKmh);
            }
        });

        producer.flush();

        // Update correlation manager
        correlationManager.updateVehicleState(vehicleId, simState.status.name(),
            simState.latitude, simState.longitude);
    }

    /**
     * Internal simulation state for each vehicle.
     */
    private static class VehicleSimState {
        final String vehicleId;
        double latitude;
        double longitude;
        double speedKmh;
        double heading;
        VehicleStatus status;
        int shipmentCount;
        double weightKg;
        double volumeCubicM;
        String driverId;
        double fuelLevel;
        double odometer;

        VehicleSimState(String vehicleId, DataCorrelationManager correlationManager) {
            this.vehicleId = vehicleId;
            this.status = VehicleStatus.IDLE;
            this.fuelLevel = 80 + ThreadLocalRandom.current().nextDouble(20);
            this.odometer = 10000 + ThreadLocalRandom.current().nextDouble(100000);
            this.driverId = correlationManager.getRandomDriverId();
            this.heading = ThreadLocalRandom.current().nextDouble(360);

            // Start near a random warehouse
            WarehouseLocation warehouse = correlationManager.getWarehouseLocation(
                correlationManager.getRandomWarehouseId());
            this.latitude = warehouse.getLatitude() + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.5;
            this.longitude = warehouse.getLongitude() + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.5;
        }

        void updateState() {
            double rand = ThreadLocalRandom.current().nextDouble();

            // Determine status based on probability distribution
            if (rand < EN_ROUTE_PROBABILITY) {
                status = VehicleStatus.EN_ROUTE;
                // Speed varies: 40-55 km/h (city) or 80-120 km/h (motorway)
                speedKmh = ThreadLocalRandom.current().nextDouble() < 0.4
                    ? 40 + ThreadLocalRandom.current().nextDouble(15)   // City
                    : 80 + ThreadLocalRandom.current().nextDouble(40);  // Motorway

                updatePosition();

            } else if (rand < EN_ROUTE_PROBABILITY + IDLE_PROBABILITY) {
                status = VehicleStatus.IDLE;
                speedKmh = 0;

            } else if (rand < EN_ROUTE_PROBABILITY + IDLE_PROBABILITY + LOADING_PROBABILITY) {
                status = ThreadLocalRandom.current().nextBoolean()
                    ? VehicleStatus.LOADING : VehicleStatus.UNLOADING;
                speedKmh = 0;

            } else {
                status = VehicleStatus.MAINTENANCE;
                speedKmh = 0;
            }

            // Update cargo based on status
            if (status == VehicleStatus.EN_ROUTE || status == VehicleStatus.LOADING) {
                shipmentCount = ThreadLocalRandom.current().nextInt(1, 20);
                weightKg = shipmentCount * (50 + ThreadLocalRandom.current().nextDouble(100));
                volumeCubicM = shipmentCount * (0.5 + ThreadLocalRandom.current().nextDouble(1.5));
            } else if (status == VehicleStatus.UNLOADING) {
                shipmentCount = Math.max(0, shipmentCount - ThreadLocalRandom.current().nextInt(1, 5));
                weightKg = shipmentCount * 75;
                volumeCubicM = shipmentCount * 1.0;
            }

            // Consume fuel when moving
            if (speedKmh > 0) {
                double intervalSeconds = 2.0; // Approximate update interval
                fuelLevel = Math.max(5, fuelLevel - 0.05 - speedKmh * 0.0005);
                odometer += speedKmh * (intervalSeconds / 3600.0);
            }

            // Refuel if low and idle
            if (fuelLevel < 10 && status == VehicleStatus.IDLE) {
                fuelLevel = 80 + ThreadLocalRandom.current().nextDouble(20);
            }
        }

        void updatePosition() {
            double intervalSeconds = 2.0;
            double distanceKm = speedKmh * (intervalSeconds / 3600.0);
            double headingRad = Math.toRadians(heading);

            // Approximate: 1 degree latitude = 111 km
            double latChange = (distanceKm * Math.cos(headingRad)) / 111.0;
            double lonChange = (distanceKm * Math.sin(headingRad)) / (111.0 * Math.cos(Math.toRadians(latitude)));

            latitude += latChange;
            longitude += lonChange;

            // Keep within European bounds
            latitude = Math.max(35, Math.min(70, latitude));
            longitude = Math.max(-10, Math.min(30, longitude));

            // Occasionally change heading
            if (ThreadLocalRandom.current().nextDouble() < 0.2) {
                heading = (heading + ThreadLocalRandom.current().nextDouble(-30, 30) + 360) % 360;
            }
        }
    }

    public static void main(String[] args) {
        LOG.info("Starting VehicleTelemetryGenerator");
        VehicleTelemetryGenerator generator = new VehicleTelemetryGenerator();
        generator.start();

        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
