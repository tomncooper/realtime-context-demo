package com.smartship.generators.model;

/**
 * Route reference data loaded from PostgreSQL.
 */
public class Route {
    private final String routeId;
    private final String originWarehouseId;
    private final String destinationCity;
    private final String destinationCountry;
    private final double distanceKm;
    private final double estimatedHours;
    private final String routeType;
    private final boolean active;

    public Route(String routeId, String originWarehouseId, String destinationCity,
                 String destinationCountry, double distanceKm, double estimatedHours,
                 String routeType, boolean active) {
        this.routeId = routeId;
        this.originWarehouseId = originWarehouseId;
        this.destinationCity = destinationCity;
        this.destinationCountry = destinationCountry;
        this.distanceKm = distanceKm;
        this.estimatedHours = estimatedHours;
        this.routeType = routeType;
        this.active = active;
    }

    public String getRouteId() { return routeId; }
    public String getOriginWarehouseId() { return originWarehouseId; }
    public String getDestinationCity() { return destinationCity; }
    public String getDestinationCountry() { return destinationCountry; }
    public double getDistanceKm() { return distanceKm; }
    public double getEstimatedHours() { return estimatedHours; }
    public String getRouteType() { return routeType; }
    public boolean isActive() { return active; }

    @Override
    public String toString() {
        return "Route{" +
                "routeId='" + routeId + '\'' +
                ", originWarehouseId='" + originWarehouseId + '\'' +
                ", destinationCity='" + destinationCity + '\'' +
                '}';
    }
}
