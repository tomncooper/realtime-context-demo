-- SmartShip Logistics Test Database Schema
-- Minimal test data for unit and integration tests

-- Drop existing tables if they exist (in correct dependency order)
DROP TABLE IF EXISTS routes CASCADE;
DROP TABLE IF EXISTS drivers CASCADE;
DROP TABLE IF EXISTS products CASCADE;
DROP TABLE IF EXISTS vehicles CASCADE;
DROP TABLE IF EXISTS customers CASCADE;
DROP TABLE IF EXISTS warehouses CASCADE;

-- ============================================
-- WAREHOUSES (3 test records)
-- ============================================
CREATE TABLE warehouses (
    warehouse_id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    city VARCHAR(100) NOT NULL,
    country VARCHAR(50) NOT NULL,
    latitude DECIMAL(10, 8),
    longitude DECIMAL(11, 8),
    status VARCHAR(20) DEFAULT 'OPERATIONAL',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO warehouses (warehouse_id, name, city, country, latitude, longitude, status) VALUES
    ('WH-RTM', 'Rotterdam Distribution Center', 'Rotterdam', 'Netherlands', 51.9225, 4.47917, 'OPERATIONAL'),
    ('WH-FRA', 'Frankfurt Logistics Hub', 'Frankfurt', 'Germany', 50.1109, 8.68213, 'OPERATIONAL'),
    ('WH-BCN', 'Barcelona Fulfillment Center', 'Barcelona', 'Spain', 41.3874, 2.16996, 'OPERATIONAL');

-- ============================================
-- CUSTOMERS (5 test records)
-- ============================================
CREATE TABLE customers (
    customer_id VARCHAR(50) PRIMARY KEY,
    company_name VARCHAR(200) NOT NULL,
    contact_email VARCHAR(200),
    sla_tier VARCHAR(20) NOT NULL CHECK (sla_tier IN ('STANDARD', 'EXPRESS', 'SAME_DAY', 'CRITICAL')),
    account_status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (account_status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO customers (customer_id, company_name, contact_email, sla_tier, account_status) VALUES
    ('CUST-0001', 'Test Company One', 'test1@example.com', 'STANDARD', 'ACTIVE'),
    ('CUST-0002', 'Test Company Two', 'test2@example.com', 'EXPRESS', 'ACTIVE'),
    ('CUST-0003', 'Test Company Three', 'test3@example.com', 'SAME_DAY', 'ACTIVE'),
    ('CUST-0004', 'Test Company Four', 'test4@example.com', 'CRITICAL', 'ACTIVE'),
    ('CUST-0005', 'Suspended Company', 'suspended@example.com', 'STANDARD', 'SUSPENDED');

-- ============================================
-- VEHICLES (3 test records)
-- ============================================
CREATE TABLE vehicles (
    vehicle_id VARCHAR(50) PRIMARY KEY,
    vehicle_type VARCHAR(30) NOT NULL CHECK (vehicle_type IN ('VAN', 'BOX_TRUCK', 'SEMI_TRAILER')),
    license_plate VARCHAR(20) NOT NULL,
    capacity_kg DECIMAL(10, 2) NOT NULL,
    capacity_cubic_m DECIMAL(8, 2) NOT NULL,
    home_warehouse_id VARCHAR(50) NOT NULL REFERENCES warehouses(warehouse_id),
    status VARCHAR(20) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'IN_USE', 'MAINTENANCE', 'RETIRED')),
    fuel_type VARCHAR(20) DEFAULT 'DIESEL' CHECK (fuel_type IN ('DIESEL', 'ELECTRIC', 'HYBRID')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO vehicles (vehicle_id, vehicle_type, license_plate, capacity_kg, capacity_cubic_m, home_warehouse_id, status, fuel_type) VALUES
    ('VEH-001', 'VAN', 'TEST-VAN-001', 1500.00, 8.00, 'WH-RTM', 'AVAILABLE', 'DIESEL'),
    ('VEH-002', 'BOX_TRUCK', 'TEST-BOX-002', 5000.00, 30.00, 'WH-FRA', 'IN_USE', 'DIESEL'),
    ('VEH-003', 'SEMI_TRAILER', 'TEST-SEM-003', 20000.00, 80.00, 'WH-BCN', 'AVAILABLE', 'DIESEL');

-- ============================================
-- DRIVERS (3 test records)
-- ============================================
CREATE TABLE drivers (
    driver_id VARCHAR(50) PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    license_type VARCHAR(10) NOT NULL CHECK (license_type IN ('B', 'C', 'CE')),
    certifications TEXT[],
    assigned_vehicle_id VARCHAR(50) REFERENCES vehicles(vehicle_id),
    home_warehouse_id VARCHAR(50) NOT NULL REFERENCES warehouses(warehouse_id),
    status VARCHAR(20) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'ON_DUTY', 'OFF_DUTY', 'ON_LEAVE')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO drivers (driver_id, first_name, last_name, license_type, certifications, assigned_vehicle_id, home_warehouse_id, status) VALUES
    ('DRV-001', 'Jan', 'van der Berg', 'C', ARRAY['ADR', 'FORKLIFT'], 'VEH-001', 'WH-RTM', 'AVAILABLE'),
    ('DRV-002', 'Hans', 'Mueller', 'CE', ARRAY['ADR', 'HAZMAT'], 'VEH-002', 'WH-FRA', 'ON_DUTY'),
    ('DRV-003', 'Carlos', 'Garcia', 'C', ARRAY['FORKLIFT'], 'VEH-003', 'WH-BCN', 'AVAILABLE');

-- ============================================
-- PRODUCTS (5 test records)
-- ============================================
CREATE TABLE products (
    product_id VARCHAR(50) PRIMARY KEY,
    sku VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(50) NOT NULL CHECK (category IN ('ELECTRONICS', 'APPAREL', 'HOME_GARDEN', 'AUTOMOTIVE', 'FOOD_BEVERAGE')),
    weight_kg DECIMAL(8, 3) NOT NULL,
    length_cm DECIMAL(8, 2) DEFAULT 0,
    width_cm DECIMAL(8, 2) DEFAULT 0,
    height_cm DECIMAL(8, 2) DEFAULT 0,
    unit_price DECIMAL(10, 2) DEFAULT 0,
    fragile BOOLEAN DEFAULT FALSE,
    hazmat BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO products (product_id, sku, name, category, weight_kg, length_cm, width_cm, height_cm, unit_price) VALUES
    ('PRD-00001', 'SKU-TEST-001', 'Test Electronics Item', 'ELECTRONICS', 1.500, 30.0, 20.0, 10.0, 299.99),
    ('PRD-00002', 'SKU-TEST-002', 'Test Apparel Item', 'APPAREL', 0.500, 40.0, 30.0, 5.0, 49.99),
    ('PRD-00003', 'SKU-TEST-003', 'Test Home Item', 'HOME_GARDEN', 5.000, 50.0, 40.0, 30.0, 129.99),
    ('PRD-00004', 'SKU-TEST-004', 'Test Auto Part', 'AUTOMOTIVE', 10.000, 60.0, 40.0, 20.0, 199.99),
    ('PRD-00005', 'SKU-TEST-005', 'Test Food Item', 'FOOD_BEVERAGE', 2.000, 25.0, 15.0, 10.0, 19.99);

-- ============================================
-- ROUTES (3 test records)
-- ============================================
CREATE TABLE routes (
    route_id VARCHAR(50) PRIMARY KEY,
    origin_warehouse_id VARCHAR(50) NOT NULL REFERENCES warehouses(warehouse_id),
    destination_city VARCHAR(100) NOT NULL,
    destination_country VARCHAR(50) NOT NULL,
    distance_km DECIMAL(10, 2) NOT NULL,
    estimated_hours DECIMAL(5, 2) NOT NULL,
    route_type VARCHAR(20) DEFAULT 'STANDARD',
    active BOOLEAN DEFAULT TRUE,
    toll_required BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO routes (route_id, origin_warehouse_id, destination_city, destination_country, distance_km, estimated_hours, route_type, active) VALUES
    ('RTE-001', 'WH-RTM', 'Amsterdam', 'Netherlands', 80.00, 1.50, 'LOCAL', TRUE),
    ('RTE-002', 'WH-FRA', 'Munich', 'Germany', 400.00, 4.00, 'REGIONAL', TRUE),
    ('RTE-003', 'WH-BCN', 'Madrid', 'Spain', 620.00, 6.00, 'LONG_HAUL', TRUE);
