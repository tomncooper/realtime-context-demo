package com.smartship.generators.model;

/**
 * Warehouse reference data loaded from PostgreSQL.
 */
public class Warehouse {
    private final String warehouseId;
    private final String name;
    private final String city;
    private final String country;
    private final double latitude;
    private final double longitude;
    private final String status;

    public Warehouse(String warehouseId, String name, String city, String country,
                     double latitude, double longitude, String status) {
        this.warehouseId = warehouseId;
        this.name = name;
        this.city = city;
        this.country = country;
        this.latitude = latitude;
        this.longitude = longitude;
        this.status = status;
    }

    public String getWarehouseId() { return warehouseId; }
    public String getName() { return name; }
    public String getCity() { return city; }
    public String getCountry() { return country; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public String getStatus() { return status; }

    @Override
    public String toString() {
        return "Warehouse{" +
                "warehouseId='" + warehouseId + '\'' +
                ", city='" + city + '\'' +
                ", country='" + country + '\'' +
                '}';
    }
}
