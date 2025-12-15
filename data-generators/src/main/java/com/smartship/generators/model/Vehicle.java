package com.smartship.generators.model;

/**
 * Vehicle reference data loaded from PostgreSQL.
 */
public class Vehicle {
    private final String vehicleId;
    private final String vehicleType;
    private final String licensePlate;
    private final double capacityKg;
    private final double capacityCubicM;
    private final String homeWarehouseId;
    private final String status;
    private final String fuelType;

    public Vehicle(String vehicleId, String vehicleType, String licensePlate,
                   double capacityKg, double capacityCubicM, String homeWarehouseId,
                   String status, String fuelType) {
        this.vehicleId = vehicleId;
        this.vehicleType = vehicleType;
        this.licensePlate = licensePlate;
        this.capacityKg = capacityKg;
        this.capacityCubicM = capacityCubicM;
        this.homeWarehouseId = homeWarehouseId;
        this.status = status;
        this.fuelType = fuelType;
    }

    public String getVehicleId() { return vehicleId; }
    public String getVehicleType() { return vehicleType; }
    public String getLicensePlate() { return licensePlate; }
    public double getCapacityKg() { return capacityKg; }
    public double getCapacityCubicM() { return capacityCubicM; }
    public String getHomeWarehouseId() { return homeWarehouseId; }
    public String getStatus() { return status; }
    public String getFuelType() { return fuelType; }

    @Override
    public String toString() {
        return "Vehicle{" +
                "vehicleId='" + vehicleId + '\'' +
                ", vehicleType='" + vehicleType + '\'' +
                ", homeWarehouseId='" + homeWarehouseId + '\'' +
                '}';
    }
}
