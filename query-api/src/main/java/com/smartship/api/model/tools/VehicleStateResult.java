package com.smartship.api.model.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Result record for individual vehicle state/telemetry.
 * Used by both LangChain4j tools and MCP tools.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VehicleStateResult(
    @JsonProperty("vehicle_id") String vehicleId,
    @JsonProperty("latitude") Double latitude,
    @JsonProperty("longitude") Double longitude,
    @JsonProperty("speed_kmh") Double speedKmh,
    @JsonProperty("heading") Double heading,
    @JsonProperty("fuel_level_percent") Double fuelLevelPercent,
    @JsonProperty("current_load_kg") Double currentLoadKg,
    @JsonProperty("current_load_m3") Double currentLoadM3,
    @JsonProperty("status") String status,
    @JsonProperty("timestamp") Long timestamp,
    @JsonProperty("message") String message
) {
    @SuppressWarnings("unchecked")
    public static VehicleStateResult of(String vehicleId, Map<String, Object> state) {
        Map<String, Object> location = (Map<String, Object>) state.get("location");
        Map<String, Object> load = (Map<String, Object>) state.get("current_load");

        return new VehicleStateResult(
            vehicleId,
            location != null ? toDouble(location.get("latitude")) : null,
            location != null ? toDouble(location.get("longitude")) : null,
            toDouble(state.get("speed_kmh")),
            toDouble(state.get("heading")),
            toDouble(state.get("fuel_level_percent")),
            load != null ? toDouble(load.get("weight_kg")) : null,
            load != null ? toDouble(load.get("volume_m3")) : null,
            toString(state.get("status")),
            toLong(state.get("timestamp")),
            null
        );
    }

    public static VehicleStateResult notFound(String vehicleId) {
        return new VehicleStateResult(
            vehicleId, null, null, null, null, null, null, null, null, null,
            "No telemetry data found for vehicle " + vehicleId + ". The vehicle may not be active."
        );
    }

    private static Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String toString(Object value) {
        return value != null ? value.toString() : null;
    }
}
