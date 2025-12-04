-- SmartShip Logistics Database Schema
-- Phase 1: Warehouses table only

-- Drop existing table if it exists
DROP TABLE IF EXISTS warehouses CASCADE;

-- Create warehouses table
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

-- Create index on status for filtering
CREATE INDEX idx_warehouses_status ON warehouses(status);

-- Seed data: 5 European warehouses
INSERT INTO warehouses (warehouse_id, name, city, country, latitude, longitude, status) VALUES
    ('WH-RTM', 'Rotterdam Distribution Center', 'Rotterdam', 'Netherlands', 51.9225, 4.47917, 'OPERATIONAL'),
    ('WH-FRA', 'Frankfurt Logistics Hub', 'Frankfurt', 'Germany', 50.1109, 8.68213, 'OPERATIONAL'),
    ('WH-BCN', 'Barcelona Fulfillment Center', 'Barcelona', 'Spain', 41.3874, 2.16996, 'OPERATIONAL'),
    ('WH-WAW', 'Warsaw Regional Depot', 'Warsaw', 'Poland', 52.2297, 21.0122, 'OPERATIONAL'),
    ('WH-STO', 'Stockholm Nordic Hub', 'Stockholm', 'Sweden', 59.3293, 18.0686, 'OPERATIONAL')
ON CONFLICT (warehouse_id) DO NOTHING;

-- Verify data
SELECT COUNT(*) as warehouse_count FROM warehouses;
SELECT * FROM warehouses ORDER BY warehouse_id;
