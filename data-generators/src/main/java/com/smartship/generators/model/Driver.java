package com.smartship.generators.model;

import java.util.List;

/**
 * Driver reference data loaded from PostgreSQL.
 */
public class Driver {
    private final String driverId;
    private final String firstName;
    private final String lastName;
    private final String licenseType;
    private final List<String> certifications;
    private final String assignedVehicleId;
    private final String homeWarehouseId;
    private final String status;

    public Driver(String driverId, String firstName, String lastName, String licenseType,
                  List<String> certifications, String assignedVehicleId,
                  String homeWarehouseId, String status) {
        this.driverId = driverId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.licenseType = licenseType;
        this.certifications = certifications;
        this.assignedVehicleId = assignedVehicleId;
        this.homeWarehouseId = homeWarehouseId;
        this.status = status;
    }

    public String getDriverId() { return driverId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getLicenseType() { return licenseType; }
    public List<String> getCertifications() { return certifications; }
    public String getAssignedVehicleId() { return assignedVehicleId; }
    public String getHomeWarehouseId() { return homeWarehouseId; }
    public String getStatus() { return status; }

    @Override
    public String toString() {
        return "Driver{" +
                "driverId='" + driverId + '\'' +
                ", name='" + firstName + " " + lastName + '\'' +
                ", licenseType='" + licenseType + '\'' +
                '}';
    }
}
