package com.smartship.generators.model;

import java.util.Collections;
import java.util.List;

/**
 * Container for all reference data loaded from PostgreSQL at startup.
 */
public class ReferenceData {
    private final List<Warehouse> warehouses;
    private final List<Customer> customers;
    private final List<Vehicle> vehicles;
    private final List<Driver> drivers;
    private final List<Product> products;
    private final List<Route> routes;

    public ReferenceData(List<Warehouse> warehouses, List<Customer> customers,
                         List<Vehicle> vehicles, List<Driver> drivers,
                         List<Product> products, List<Route> routes) {
        this.warehouses = Collections.unmodifiableList(warehouses);
        this.customers = Collections.unmodifiableList(customers);
        this.vehicles = Collections.unmodifiableList(vehicles);
        this.drivers = Collections.unmodifiableList(drivers);
        this.products = Collections.unmodifiableList(products);
        this.routes = Collections.unmodifiableList(routes);
    }

    public List<Warehouse> getWarehouses() { return warehouses; }
    public List<Customer> getCustomers() { return customers; }
    public List<Vehicle> getVehicles() { return vehicles; }
    public List<Driver> getDrivers() { return drivers; }
    public List<Product> getProducts() { return products; }
    public List<Route> getRoutes() { return routes; }

    @Override
    public String toString() {
        return "ReferenceData{" +
                "warehouses=" + warehouses.size() +
                ", customers=" + customers.size() +
                ", vehicles=" + vehicles.size() +
                ", drivers=" + drivers.size() +
                ", products=" + products.size() +
                ", routes=" + routes.size() +
                '}';
    }
}
