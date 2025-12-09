-- SmartShip Logistics Database Schema
-- Phase 2: Full reference data tables

-- Drop existing tables if they exist (in correct dependency order)
DROP TABLE IF EXISTS routes CASCADE;
DROP TABLE IF EXISTS drivers CASCADE;
DROP TABLE IF EXISTS products CASCADE;
DROP TABLE IF EXISTS vehicles CASCADE;
DROP TABLE IF EXISTS customers CASCADE;
DROP TABLE IF EXISTS warehouses CASCADE;

-- ============================================
-- WAREHOUSES (5 records - European locations)
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

CREATE INDEX idx_warehouses_status ON warehouses(status);

INSERT INTO warehouses (warehouse_id, name, city, country, latitude, longitude, status) VALUES
    ('WH-RTM', 'Rotterdam Distribution Center', 'Rotterdam', 'Netherlands', 51.9225, 4.47917, 'OPERATIONAL'),
    ('WH-FRA', 'Frankfurt Logistics Hub', 'Frankfurt', 'Germany', 50.1109, 8.68213, 'OPERATIONAL'),
    ('WH-BCN', 'Barcelona Fulfillment Center', 'Barcelona', 'Spain', 41.3874, 2.16996, 'OPERATIONAL'),
    ('WH-WAW', 'Warsaw Regional Depot', 'Warsaw', 'Poland', 52.2297, 21.0122, 'OPERATIONAL'),
    ('WH-STO', 'Stockholm Nordic Hub', 'Stockholm', 'Sweden', 59.3293, 18.0686, 'OPERATIONAL')
ON CONFLICT (warehouse_id) DO NOTHING;

-- ============================================
-- CUSTOMERS (200 records)
-- ============================================
CREATE TABLE customers (
    customer_id VARCHAR(50) PRIMARY KEY,
    company_name VARCHAR(200) NOT NULL,
    contact_email VARCHAR(200),
    sla_tier VARCHAR(20) NOT NULL CHECK (sla_tier IN ('STANDARD', 'EXPRESS', 'SAME_DAY', 'CRITICAL')),
    account_status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (account_status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_customers_sla_tier ON customers(sla_tier);
CREATE INDEX idx_customers_account_status ON customers(account_status);

-- Generate 200 customers using generate_series
INSERT INTO customers (customer_id, company_name, contact_email, sla_tier, account_status)
SELECT
    'CUST-' || LPAD(n::text, 4, '0'),
    CASE (n % 10)
        WHEN 0 THEN 'Acme Corp ' || n
        WHEN 1 THEN 'Global Trade ' || n
        WHEN 2 THEN 'Euro Logistics ' || n
        WHEN 3 THEN 'Nordic Supplies ' || n
        WHEN 4 THEN 'Baltic Commerce ' || n
        WHEN 5 THEN 'Rhine Industries ' || n
        WHEN 6 THEN 'Atlantic Partners ' || n
        WHEN 7 THEN 'Continental Goods ' || n
        WHEN 8 THEN 'Union Traders ' || n
        ELSE 'Express Holdings ' || n
    END,
    'contact' || n || '@company' || n || '.eu',
    CASE
        WHEN n <= 20 THEN 'CRITICAL'
        WHEN n <= 60 THEN 'SAME_DAY'
        WHEN n <= 120 THEN 'EXPRESS'
        ELSE 'STANDARD'
    END,
    CASE WHEN n % 50 = 0 THEN 'SUSPENDED' ELSE 'ACTIVE' END
FROM generate_series(1, 200) AS n
ON CONFLICT (customer_id) DO NOTHING;

-- ============================================
-- VEHICLES (50 records)
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

CREATE INDEX idx_vehicles_home_warehouse ON vehicles(home_warehouse_id);
CREATE INDEX idx_vehicles_status ON vehicles(status);
CREATE INDEX idx_vehicles_type ON vehicles(vehicle_type);

-- Generate 50 vehicles distributed across warehouses
-- 60% vans, 30% box trucks, 10% semi-trailers
INSERT INTO vehicles (vehicle_id, vehicle_type, license_plate, capacity_kg, capacity_cubic_m, home_warehouse_id, status, fuel_type)
SELECT
    'VEH-' || LPAD(n::text, 3, '0'),
    CASE
        WHEN n <= 30 THEN 'VAN'
        WHEN n <= 45 THEN 'BOX_TRUCK'
        ELSE 'SEMI_TRAILER'
    END,
    CASE ((n - 1) / 10)
        WHEN 0 THEN 'NL-'
        WHEN 1 THEN 'DE-'
        WHEN 2 THEN 'ES-'
        WHEN 3 THEN 'PL-'
        ELSE 'SE-'
    END || LPAD(n::text, 4, '0'),
    CASE
        WHEN n <= 30 THEN 1500.00    -- Van capacity
        WHEN n <= 45 THEN 5000.00    -- Box truck capacity
        ELSE 20000.00                 -- Semi-trailer capacity
    END,
    CASE
        WHEN n <= 30 THEN 12.00      -- Van volume
        WHEN n <= 45 THEN 40.00      -- Box truck volume
        ELSE 80.00                    -- Semi-trailer volume
    END,
    CASE ((n - 1) / 10)
        WHEN 0 THEN 'WH-RTM'
        WHEN 1 THEN 'WH-FRA'
        WHEN 2 THEN 'WH-BCN'
        WHEN 3 THEN 'WH-WAW'
        ELSE 'WH-STO'
    END,
    CASE WHEN n % 12 = 0 THEN 'MAINTENANCE' ELSE 'AVAILABLE' END,
    CASE WHEN n % 5 = 0 THEN 'ELECTRIC' WHEN n % 7 = 0 THEN 'HYBRID' ELSE 'DIESEL' END
FROM generate_series(1, 50) AS n
ON CONFLICT (vehicle_id) DO NOTHING;

-- ============================================
-- PRODUCTS (10,000 records)
-- ============================================
CREATE TABLE products (
    product_id VARCHAR(50) PRIMARY KEY,
    sku VARCHAR(50) UNIQUE NOT NULL,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(50) NOT NULL,
    weight_kg DECIMAL(8, 3) NOT NULL,
    length_cm DECIMAL(8, 2),
    width_cm DECIMAL(8, 2),
    height_cm DECIMAL(8, 2),
    unit_price DECIMAL(10, 2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_products_category ON products(category);
CREATE INDEX idx_products_sku ON products(sku);

-- Generate 10,000 products across 5 categories
INSERT INTO products (product_id, sku, name, category, weight_kg, length_cm, width_cm, height_cm, unit_price)
SELECT
    'PROD-' || LPAD(n::text, 6, '0'),
    'SKU-' || LPAD(n::text, 8, '0'),
    CASE (n % 5)
        WHEN 0 THEN 'Electronics Item '
        WHEN 1 THEN 'Home Goods '
        WHEN 2 THEN 'Apparel Item '
        WHEN 3 THEN 'Automotive Part '
        ELSE 'General Merchandise '
    END || n,
    CASE (n % 5)
        WHEN 0 THEN 'ELECTRONICS'
        WHEN 1 THEN 'HOME_GOODS'
        WHEN 2 THEN 'APPAREL'
        WHEN 3 THEN 'AUTOMOTIVE'
        ELSE 'GENERAL'
    END,
    (0.1 + (n % 100) * 0.15)::DECIMAL(8, 3),  -- Weight: 0.1 to 15 kg
    (5 + (n % 80))::DECIMAL(8, 2),             -- Length: 5 to 85 cm
    (5 + (n % 60))::DECIMAL(8, 2),             -- Width: 5 to 65 cm
    (2 + (n % 40))::DECIMAL(8, 2),             -- Height: 2 to 42 cm
    (5.00 + (n % 500) * 2.50)::DECIMAL(10, 2)  -- Price: 5 to 1250 EUR
FROM generate_series(1, 10000) AS n
ON CONFLICT (product_id) DO NOTHING;

-- ============================================
-- DRIVERS (75 records)
-- ============================================
CREATE TABLE drivers (
    driver_id VARCHAR(50) PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    license_type VARCHAR(20) NOT NULL CHECK (license_type IN ('B', 'C', 'CE')),
    certifications TEXT[],
    assigned_vehicle_id VARCHAR(50) REFERENCES vehicles(vehicle_id),
    home_warehouse_id VARCHAR(50) REFERENCES warehouses(warehouse_id),
    status VARCHAR(20) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'ON_DUTY', 'OFF_DUTY', 'VACATION')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_drivers_status ON drivers(status);
CREATE INDEX idx_drivers_home_warehouse ON drivers(home_warehouse_id);
CREATE INDEX idx_drivers_assigned_vehicle ON drivers(assigned_vehicle_id);

-- Generate 75 drivers (1.5 drivers per vehicle average)
INSERT INTO drivers (driver_id, first_name, last_name, license_type, certifications, assigned_vehicle_id, home_warehouse_id, status)
SELECT
    'DRV-' || LPAD(n::text, 3, '0'),
    CASE (n % 10)
        WHEN 0 THEN 'Hans' WHEN 1 THEN 'Pierre' WHEN 2 THEN 'Maria'
        WHEN 3 THEN 'Erik' WHEN 4 THEN 'Sofia' WHEN 5 THEN 'Jan'
        WHEN 6 THEN 'Anna' WHEN 7 THEN 'Miguel' WHEN 8 THEN 'Olga'
        ELSE 'Lars'
    END,
    CASE (n % 8)
        WHEN 0 THEN 'Mueller' WHEN 1 THEN 'Dupont' WHEN 2 THEN 'Garcia'
        WHEN 3 THEN 'Kowalski' WHEN 4 THEN 'Andersson' WHEN 5 THEN 'Schmidt'
        WHEN 6 THEN 'Jansen' ELSE 'Petrov'
    END,
    CASE
        WHEN n <= 30 THEN 'B'   -- Van license
        WHEN n <= 60 THEN 'C'   -- Truck license
        ELSE 'CE'               -- Articulated truck license
    END,
    CASE (n % 4)
        WHEN 0 THEN ARRAY['ADR']
        WHEN 1 THEN ARRAY['HACCP']
        WHEN 2 THEN ARRAY['ADR', 'HACCP']
        ELSE ARRAY[]::TEXT[]
    END,
    CASE WHEN n <= 50 THEN 'VEH-' || LPAD(n::text, 3, '0') ELSE NULL END,
    CASE ((n - 1) / 15)
        WHEN 0 THEN 'WH-RTM'
        WHEN 1 THEN 'WH-FRA'
        WHEN 2 THEN 'WH-BCN'
        WHEN 3 THEN 'WH-WAW'
        ELSE 'WH-STO'
    END,
    CASE
        WHEN n % 10 = 0 THEN 'VACATION'
        WHEN n % 5 = 0 THEN 'OFF_DUTY'
        ELSE 'AVAILABLE'
    END
FROM generate_series(1, 75) AS n
ON CONFLICT (driver_id) DO NOTHING;

-- ============================================
-- ROUTES (100 records)
-- ============================================
CREATE TABLE routes (
    route_id VARCHAR(50) PRIMARY KEY,
    origin_warehouse_id VARCHAR(50) NOT NULL REFERENCES warehouses(warehouse_id),
    destination_city VARCHAR(100) NOT NULL,
    destination_country VARCHAR(50) NOT NULL,
    distance_km DECIMAL(10, 2) NOT NULL,
    estimated_hours DECIMAL(6, 2) NOT NULL,
    route_type VARCHAR(20) DEFAULT 'DIRECT' CHECK (route_type IN ('DIRECT', 'HUB_SPOKE', 'MULTI_STOP')),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_routes_origin ON routes(origin_warehouse_id);
CREATE INDEX idx_routes_destination_city ON routes(destination_city);
CREATE INDEX idx_routes_active ON routes(active);

-- Generate 100 routes (20 per warehouse to major European cities)
INSERT INTO routes (route_id, origin_warehouse_id, destination_city, destination_country, distance_km, estimated_hours, route_type)
SELECT
    'RTE-' || LPAD(n::text, 3, '0'),
    CASE ((n - 1) / 20)
        WHEN 0 THEN 'WH-RTM'
        WHEN 1 THEN 'WH-FRA'
        WHEN 2 THEN 'WH-BCN'
        WHEN 3 THEN 'WH-WAW'
        ELSE 'WH-STO'
    END,
    CASE (n % 20)
        WHEN 0 THEN 'Amsterdam' WHEN 1 THEN 'Berlin' WHEN 2 THEN 'Paris'
        WHEN 3 THEN 'Madrid' WHEN 4 THEN 'Rome' WHEN 5 THEN 'Vienna'
        WHEN 6 THEN 'Prague' WHEN 7 THEN 'Budapest' WHEN 8 THEN 'Copenhagen'
        WHEN 9 THEN 'Oslo' WHEN 10 THEN 'Helsinki' WHEN 11 THEN 'Brussels'
        WHEN 12 THEN 'Zurich' WHEN 13 THEN 'Milan' WHEN 14 THEN 'Munich'
        WHEN 15 THEN 'Hamburg' WHEN 16 THEN 'Lyon' WHEN 17 THEN 'Lisbon'
        WHEN 18 THEN 'Dublin' ELSE 'London'
    END,
    CASE (n % 20)
        WHEN 0 THEN 'Netherlands' WHEN 1 THEN 'Germany' WHEN 2 THEN 'France'
        WHEN 3 THEN 'Spain' WHEN 4 THEN 'Italy' WHEN 5 THEN 'Austria'
        WHEN 6 THEN 'Czech Republic' WHEN 7 THEN 'Hungary' WHEN 8 THEN 'Denmark'
        WHEN 9 THEN 'Norway' WHEN 10 THEN 'Finland' WHEN 11 THEN 'Belgium'
        WHEN 12 THEN 'Switzerland' WHEN 13 THEN 'Italy' WHEN 14 THEN 'Germany'
        WHEN 15 THEN 'Germany' WHEN 16 THEN 'France' WHEN 17 THEN 'Portugal'
        WHEN 18 THEN 'Ireland' ELSE 'United Kingdom'
    END,
    (100 + (n * 15) + (n % 7) * 50)::DECIMAL(10, 2),  -- Distance: 115 to ~2000 km
    (2 + (n * 0.2) + (n % 5) * 0.5)::DECIMAL(6, 2),   -- Time: 2 to ~25 hours
    CASE (n % 3)
        WHEN 0 THEN 'DIRECT'
        WHEN 1 THEN 'HUB_SPOKE'
        ELSE 'MULTI_STOP'
    END
FROM generate_series(1, 100) AS n
ON CONFLICT (route_id) DO NOTHING;

-- ============================================
-- Verification Queries
-- ============================================
SELECT 'warehouses' as table_name, COUNT(*) as record_count FROM warehouses
UNION ALL SELECT 'customers', COUNT(*) FROM customers
UNION ALL SELECT 'vehicles', COUNT(*) FROM vehicles
UNION ALL SELECT 'products', COUNT(*) FROM products
UNION ALL SELECT 'drivers', COUNT(*) FROM drivers
UNION ALL SELECT 'routes', COUNT(*) FROM routes;
