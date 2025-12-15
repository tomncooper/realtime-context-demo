package com.smartship.api.model.hybrid;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Enriched vehicle state combining real-time telemetry from Kafka Streams
 * with vehicle and driver reference data from PostgreSQL.
 */
public record EnrichedVehicleState(
    // Real-time data from Kafka Streams (vehicle-current-state store)
    @JsonProperty("vehicle_id") String vehicleId,
    @JsonProperty("status") String status,
    @JsonProperty("latitude") double latitude,
    @JsonProperty("longitude") double longitude,
    @JsonProperty("speed_kmh") double speedKmh,
    @JsonProperty("heading") double heading,
    @JsonProperty("fuel_level_percent") double fuelLevelPercent,
    @JsonProperty("current_load_kg") double currentLoadKg,
    @JsonProperty("current_load_volume_m3") double currentLoadVolumeM3,
    @JsonProperty("current_shipment_id") String currentShipmentId,
    @JsonProperty("telemetry_timestamp") long telemetryTimestamp,

    // Reference data from PostgreSQL (vehicles table)
    @JsonProperty("vehicle_type") String vehicleType,
    @JsonProperty("license_plate") String licensePlate,
    @JsonProperty("capacity_kg") double capacityKg,
    @JsonProperty("capacity_cubic_m") double capacityCubicM,
    @JsonProperty("home_warehouse_id") String homeWarehouseId,
    @JsonProperty("fuel_type") String fuelType,

    // Reference data from PostgreSQL (drivers table) - may be null
    @JsonProperty("driver_id") String driverId,
    @JsonProperty("driver_name") String driverName,
    @JsonProperty("license_type") String licenseType,
    @JsonProperty("certifications") List<String> certifications,

    // Reference data from PostgreSQL (warehouses table)
    @JsonProperty("home_warehouse_name") String homeWarehouseName,
    @JsonProperty("home_warehouse_city") String homeWarehouseCity,

    // Computed metrics
    @JsonProperty("load_percentage") double loadPercentage,
    @JsonProperty("last_updated") long lastUpdated
) {
    /**
     * Builder for creating EnrichedVehicleState from separate data sources.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String vehicleId;
        private String status;
        private double latitude;
        private double longitude;
        private double speedKmh;
        private double heading;
        private double fuelLevelPercent;
        private double currentLoadKg;
        private double currentLoadVolumeM3;
        private String currentShipmentId;
        private long telemetryTimestamp;
        private String vehicleType;
        private String licensePlate;
        private double capacityKg;
        private double capacityCubicM;
        private String homeWarehouseId;
        private String fuelType;
        private String driverId;
        private String driverName;
        private String licenseType;
        private List<String> certifications;
        private String homeWarehouseName;
        private String homeWarehouseCity;
        private double loadPercentage;
        private long lastUpdated;

        public Builder vehicleId(String vehicleId) {
            this.vehicleId = vehicleId;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder latitude(double latitude) {
            this.latitude = latitude;
            return this;
        }

        public Builder longitude(double longitude) {
            this.longitude = longitude;
            return this;
        }

        public Builder speedKmh(double speedKmh) {
            this.speedKmh = speedKmh;
            return this;
        }

        public Builder heading(double heading) {
            this.heading = heading;
            return this;
        }

        public Builder fuelLevelPercent(double fuelLevelPercent) {
            this.fuelLevelPercent = fuelLevelPercent;
            return this;
        }

        public Builder currentLoadKg(double currentLoadKg) {
            this.currentLoadKg = currentLoadKg;
            return this;
        }

        public Builder currentLoadVolumeM3(double currentLoadVolumeM3) {
            this.currentLoadVolumeM3 = currentLoadVolumeM3;
            return this;
        }

        public Builder currentShipmentId(String currentShipmentId) {
            this.currentShipmentId = currentShipmentId;
            return this;
        }

        public Builder telemetryTimestamp(long telemetryTimestamp) {
            this.telemetryTimestamp = telemetryTimestamp;
            return this;
        }

        public Builder vehicleType(String vehicleType) {
            this.vehicleType = vehicleType;
            return this;
        }

        public Builder licensePlate(String licensePlate) {
            this.licensePlate = licensePlate;
            return this;
        }

        public Builder capacityKg(double capacityKg) {
            this.capacityKg = capacityKg;
            return this;
        }

        public Builder capacityCubicM(double capacityCubicM) {
            this.capacityCubicM = capacityCubicM;
            return this;
        }

        public Builder homeWarehouseId(String homeWarehouseId) {
            this.homeWarehouseId = homeWarehouseId;
            return this;
        }

        public Builder fuelType(String fuelType) {
            this.fuelType = fuelType;
            return this;
        }

        public Builder driverId(String driverId) {
            this.driverId = driverId;
            return this;
        }

        public Builder driverName(String driverName) {
            this.driverName = driverName;
            return this;
        }

        public Builder licenseType(String licenseType) {
            this.licenseType = licenseType;
            return this;
        }

        public Builder certifications(List<String> certifications) {
            this.certifications = certifications;
            return this;
        }

        public Builder homeWarehouseName(String homeWarehouseName) {
            this.homeWarehouseName = homeWarehouseName;
            return this;
        }

        public Builder homeWarehouseCity(String homeWarehouseCity) {
            this.homeWarehouseCity = homeWarehouseCity;
            return this;
        }

        public Builder loadPercentage(double loadPercentage) {
            this.loadPercentage = loadPercentage;
            return this;
        }

        public Builder lastUpdated(long lastUpdated) {
            this.lastUpdated = lastUpdated;
            return this;
        }

        public EnrichedVehicleState build() {
            return new EnrichedVehicleState(
                vehicleId, status, latitude, longitude, speedKmh, heading,
                fuelLevelPercent, currentLoadKg, currentLoadVolumeM3, currentShipmentId, telemetryTimestamp,
                vehicleType, licensePlate, capacityKg, capacityCubicM, homeWarehouseId, fuelType,
                driverId, driverName, licenseType, certifications,
                homeWarehouseName, homeWarehouseCity, loadPercentage, lastUpdated
            );
        }
    }
}
