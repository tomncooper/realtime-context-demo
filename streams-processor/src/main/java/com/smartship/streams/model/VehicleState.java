package com.smartship.streams.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartship.logistics.events.VehicleTelemetry;

import java.util.List;

/**
 * State store value representing the current state of a vehicle.
 * Updated from vehicle.telemetry events.
 */
public record VehicleState(
    @JsonProperty("vehicle_id") String vehicleId,
    @JsonProperty("driver_id") String driverId,
    double latitude,
    double longitude,
    @JsonProperty("speed_kmh") double speedKmh,
    @JsonProperty("fuel_level_percent") double fuelLevelPercent,
    String status,
    @JsonProperty("current_load_kg") double currentLoadKg,
    @JsonProperty("current_load_items") int currentLoadItems,
    @JsonProperty("shipment_ids") List<String> shipmentIds,
    @JsonProperty("odometer_km") double odometerKm,
    @JsonProperty("last_updated") long lastUpdated
) {
    /**
     * Create a VehicleState from a VehicleTelemetry event.
     */
    public static VehicleState from(VehicleTelemetry telemetry) {
        return new VehicleState(
            telemetry.getVehicleId(),
            telemetry.getDriverId(),
            telemetry.getLocation().getLatitude(),
            telemetry.getLocation().getLongitude(),
            telemetry.getLocation().getSpeedKmh(),
            telemetry.getFuelLevelPercent(),
            telemetry.getStatus().toString(),
            telemetry.getCurrentLoad().getWeightKg(),
            telemetry.getCurrentLoad().getShipmentCount(),
            List.of(), // Shipment IDs would need to be tracked separately
            telemetry.getOdometerKm(),
            telemetry.getTimestamp()
        );
    }
}
