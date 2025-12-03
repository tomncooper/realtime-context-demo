# Synthetic Business Data Proposal
## Real-Time Logistics & Supply Chain Demonstration

### Overview

This demonstration simulates a **regional logistics and fulfillment company** that manages multi-warehouse distribution, fleet operations, and last-mile delivery services. The synthetic data will support an LLM-based agent that answers complex operational questions by querying real-time streams, reference data, and historical analytics.

### Business Scenario

**SmartShip Logistics** operates a network of warehouses serving e-commerce and retail customers. The company:
- Manages 5 regional warehouses
- Operates a fleet of 50 delivery vehicles (vans and trucks)
- Serves 200+ active customers with varying SLA requirements
- Processes 500-2000 shipments daily
- Handles 10,000+ SKUs across multiple product categories

### Target LLM Agent Use Cases

The agent should be able to answer questions like:

1. **Real-time Operations**: "Which shipments for ACME Corp are currently delayed and why?"
2. **Fleet Optimization**: "Show me available vehicles near Rotterdam warehouse with capacity for urgent pickups"
3. **Performance Analysis**: "How does today's on-time delivery rate compare to our historical average?"
4. **Customer Service**: "What's the status of order #12345 and when will it arrive?"
5. **Operational Intelligence**: "Which warehouse has the highest exception rate this week and what are the common issues?"
6. **Predictive Insights**: "Based on current traffic and weather, which in-transit shipments are at risk of missing their SLA?"

---

## Data Architecture

### 1. Real-Time Event Streams (Apache Kafka Topics)

All Kafka topics use **Apache Avro** schemas stored in **Apicurio Registry**. Schemas are versioned and managed centrally, with producers and consumers using the Apicurio Registry Serdes library for automatic serialization/deserialization.

#### Topic: `shipment.events`
**Purpose**: Track shipment lifecycle events
**Event Rate**: 50-80 events/second
**Partition Key**: `shipment_id`

**Avro Schema** (`shipment-event.avsc`):
```json
{
  "type": "record",
  "name": "ShipmentEvent",
  "namespace": "com.smartship.logistics.events",
  "doc": "Event representing a shipment lifecycle state change",
  "fields": [
    {
      "name": "event_id",
      "type": "string",
      "doc": "Unique identifier for this event"
    },
    {
      "name": "shipment_id",
      "type": "string",
      "doc": "Unique identifier for the shipment"
    },
    {
      "name": "order_id",
      "type": "string",
      "doc": "Reference to the parent order"
    },
    {
      "name": "event_type",
      "type": {
        "type": "enum",
        "name": "ShipmentEventType",
        "symbols": ["CREATED", "PICKED", "PACKED", "DISPATCHED", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "EXCEPTION", "CANCELLED"]
      },
      "doc": "Type of shipment event"
    },
    {
      "name": "timestamp",
      "type": "long",
      "logicalType": "timestamp-millis",
      "doc": "Event occurrence timestamp in milliseconds since epoch"
    },
    {
      "name": "warehouse_id",
      "type": "string",
      "doc": "Warehouse where event occurred"
    },
    {
      "name": "vehicle_id",
      "type": ["null", "string"],
      "default": null,
      "doc": "Vehicle assigned to shipment (if applicable)"
    },
    {
      "name": "location",
      "type": {
        "type": "record",
        "name": "Location",
        "fields": [
          {"name": "latitude", "type": "double"},
          {"name": "longitude", "type": "double"},
          {"name": "address", "type": "string"}
        ]
      },
      "doc": "Geographic location of the event"
    },
    {
      "name": "metadata",
      "type": {
        "type": "record",
        "name": "EventMetadata",
        "fields": [
          {"name": "operator_id", "type": "string"},
          {"name": "exception_reason", "type": ["null", "string"], "default": null},
          {"name": "notes", "type": ["null", "string"], "default": null}
        ]
      },
      "doc": "Additional event metadata"
    }
  ]
}
```

**Data Generation Rules**:
- Shipments follow realistic lifecycle: CREATED → PICKED → PACKED → DISPATCHED → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED
- 5% of shipments encounter EXCEPTION events (weather delays, address issues, damaged goods)
- 2% of shipments are CANCELLED
- Time between lifecycle events varies: picking (10-30 min), packing (5-15 min), in-transit (2-8 hours)

#### Topic: `vehicle.telemetry`
**Purpose**: Real-time vehicle location and status updates
**Event Rate**: 20-30 events/second
**Partition Key**: `vehicle_id`

**Avro Schema** (`vehicle-telemetry.avsc`):
```json
{
  "type": "record",
  "name": "VehicleTelemetry",
  "namespace": "com.smartship.logistics.telemetry",
  "doc": "Real-time vehicle telemetry data",
  "fields": [
    {
      "name": "vehicle_id",
      "type": "string",
      "doc": "Unique identifier for the vehicle"
    },
    {
      "name": "timestamp",
      "type": "long",
      "logicalType": "timestamp-millis",
      "doc": "Telemetry capture timestamp"
    },
    {
      "name": "location",
      "type": {
        "type": "record",
        "name": "VehicleLocation",
        "fields": [
          {"name": "latitude", "type": "double"},
          {"name": "longitude", "type": "double"},
          {"name": "speed_kmh", "type": "double", "doc": "Speed in kilometers per hour"},
          {"name": "heading", "type": "int", "doc": "Compass heading 0-359 degrees"}
        ]
      },
      "doc": "Current vehicle location and movement data"
    },
    {
      "name": "status",
      "type": {
        "type": "enum",
        "name": "VehicleStatus",
        "symbols": ["IDLE", "EN_ROUTE", "LOADING", "UNLOADING", "MAINTENANCE"]
      },
      "doc": "Current vehicle operational status"
    },
    {
      "name": "current_load",
      "type": {
        "type": "record",
        "name": "VehicleLoad",
        "fields": [
          {"name": "shipment_count", "type": "int"},
          {"name": "weight_kg", "type": "double", "doc": "Total weight in kilograms"},
          {"name": "volume_cubic_m", "type": "double", "doc": "Total volume in cubic meters"}
        ]
      },
      "doc": "Current cargo load information"
    },
    {
      "name": "driver_id",
      "type": "string",
      "doc": "Current driver identifier"
    },
    {
      "name": "fuel_level_percent",
      "type": "double",
      "doc": "Fuel level as percentage (0-100)"
    },
    {
      "name": "odometer_km",
      "type": "double",
      "doc": "Total distance traveled in kilometers"
    }
  ]
}
```

**Data Generation Rules**:
- Updates every 30-60 seconds per vehicle
- Vehicles follow realistic routes between warehouses and delivery zones
- Speed varies: 0 km/h (loading/unloading), 40-55 km/h (city), 80-120 km/h (motorway)
- Fuel decreases realistically based on distance traveled
- 10% of vehicles in LOADING/UNLOADING, 60% EN_ROUTE, 25% IDLE, 5% MAINTENANCE

#### Topic: `warehouse.operations`
**Purpose**: Track warehouse-level operational events
**Event Rate**: 15-25 events/second
**Partition Key**: `warehouse_id`

**Avro Schema** (`warehouse-operation.avsc`):
```json
{
  "type": "record",
  "name": "WarehouseOperation",
  "namespace": "com.smartship.logistics.warehouse",
  "doc": "Warehouse operational event",
  "fields": [
    {
      "name": "event_id",
      "type": "string",
      "doc": "Unique identifier for this operation event"
    },
    {
      "name": "warehouse_id",
      "type": "string",
      "doc": "Warehouse where operation occurred"
    },
    {
      "name": "timestamp",
      "type": "long",
      "logicalType": "timestamp-millis",
      "doc": "Operation timestamp"
    },
    {
      "name": "operation_type",
      "type": {
        "type": "enum",
        "name": "OperationType",
        "symbols": ["RECEIVING", "PUTAWAY", "PICK", "PACK", "LOAD", "INVENTORY_ADJUSTMENT", "CYCLE_COUNT"]
      },
      "doc": "Type of warehouse operation"
    },
    {
      "name": "operator_id",
      "type": "string",
      "doc": "Warehouse worker performing the operation"
    },
    {
      "name": "product_id",
      "type": "string",
      "doc": "Product involved in the operation"
    },
    {
      "name": "quantity",
      "type": "int",
      "doc": "Number of units"
    },
    {
      "name": "location",
      "type": "string",
      "doc": "Warehouse location (zone/aisle/bin)"
    },
    {
      "name": "shipment_id",
      "type": ["null", "string"],
      "default": null,
      "doc": "Associated shipment if applicable"
    },
    {
      "name": "duration_seconds",
      "type": "int",
      "doc": "Time taken to complete operation"
    },
    {
      "name": "errors",
      "type": {
        "type": "array",
        "items": "string"
      },
      "default": [],
      "doc": "List of errors encountered during operation"
    }
  ]
}
```

**Data Generation Rules**:
- Operations distributed across 5 warehouses
- Peak activity hours: 8 AM - 6 PM local time
- 3% error rate (wrong item picked, damaged goods, location mismatch)
- Pick/pack operations linked to active shipments

#### Topic: `order.status`
**Purpose**: Customer order status changes
**Event Rate**: 10-15 events/second
**Partition Key**: `order_id`

**Avro Schema** (`order-status.avsc`):
```json
{
  "type": "record",
  "name": "OrderStatus",
  "namespace": "com.smartship.logistics.orders",
  "doc": "Order status change event",
  "fields": [
    {
      "name": "order_id",
      "type": "string",
      "doc": "Unique order identifier"
    },
    {
      "name": "customer_id",
      "type": "string",
      "doc": "Customer who placed the order"
    },
    {
      "name": "timestamp",
      "type": "long",
      "logicalType": "timestamp-millis",
      "doc": "Status change timestamp"
    },
    {
      "name": "status",
      "type": {
        "type": "enum",
        "name": "OrderStatusType",
        "symbols": ["RECEIVED", "VALIDATED", "ALLOCATED", "SHIPPED", "DELIVERED", "CANCELLED", "RETURNED"]
      },
      "doc": "Current order status"
    },
    {
      "name": "shipment_ids",
      "type": {
        "type": "array",
        "items": "string"
      },
      "default": [],
      "doc": "List of shipment IDs associated with this order"
    },
    {
      "name": "total_items",
      "type": "int",
      "doc": "Total number of items in the order"
    },
    {
      "name": "sla_timestamp",
      "type": "long",
      "logicalType": "timestamp-millis",
      "doc": "SLA deadline timestamp"
    },
    {
      "name": "priority",
      "type": {
        "type": "enum",
        "name": "OrderPriority",
        "symbols": ["STANDARD", "EXPRESS", "SAME_DAY", "CRITICAL"]
      },
      "doc": "Order priority level"
    }
  ]
}
```

**Data Generation Rules**:
- Orders can contain 1-10 items, may require multiple shipments
- SLA varies: STANDARD (5 days), EXPRESS (2 days), SAME_DAY (12 hours), CRITICAL (4 hours)
- 90% on-time, 5% late, 5% early
- Order-to-shipment ratio: 1:1.2 (some orders split across multiple shipments)

---

### Schema Registry Configuration (Apicurio Registry)

**Apicurio Registry** serves as the central schema repository for all Kafka topics, providing:
- Centralized schema management and versioning
- Schema validation for producers and consumers
- Backward/forward/full compatibility checking
- REST API for schema CRUD operations

#### Registry Configuration

**Deployment**: Apicurio Registry instance (containerized or standalone)
**Storage Backend**: PostgreSQL (for production) or in-memory (for development)
**API Endpoint**: `http://apicurio-registry:8080/apis/registry/v2`

#### Schema Artifact Groups

Schemas are organized into logical artifact groups:
- `com.smartship.logistics.events` - Shipment and operational events
- `com.smartship.logistics.telemetry` - Vehicle telemetry data
- `com.smartship.logistics.warehouse` - Warehouse operations
- `com.smartship.logistics.orders` - Order management events

#### Maven Dependencies for Java Applications

```xml
<dependencies>
  <!-- Apicurio Registry Avro Serdes -->
  <dependency>
    <groupId>io.apicurio</groupId>
    <artifactId>apicurio-registry-serdes-avro-serde</artifactId>
    <version>3.0.0.M4</version>
  </dependency>

  <!-- Apache Avro -->
  <dependency>
    <groupId>org.apache.avro</groupId>
    <artifactId>avro</artifactId>
    <version>1.12.1</version>
  </dependency>

  <!-- Kafka Clients -->
  <dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
    <version>4.1.1</version>
  </dependency>
</dependencies>
```

#### Producer Configuration Example

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-broker:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
          "io.apicurio.registry.serde.avro.AvroKafkaSerializer");

// Apicurio Registry configuration
props.put("apicurio.registry.url", "http://apicurio-registry:8080/apis/registry/v2");
props.put("apicurio.registry.auto-register", "true");
props.put("apicurio.registry.find-latest", "true");

// Avro serialization configuration
props.put("apicurio.registry.avro.datum-provider",
          "io.apicurio.registry.serde.avro.ReflectAvroDatumProvider");

KafkaProducer<String, ShipmentEvent> producer = new KafkaProducer<>(props);
```

#### Consumer Configuration Example

```java
Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-broker:9092");
props.put(ConsumerConfig.GROUP_ID_CONFIG, "shipment-processor");
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
          "io.apicurio.registry.serde.avro.AvroKafkaDeserializer");

// Apicurio Registry configuration
props.put("apicurio.registry.url", "http://apicurio-registry:8080/apis/registry/v2");
props.put("apicurio.registry.avro-datum-provider",
          "io.apicurio.registry.serde.avro.ReflectAvroDatumProvider");

KafkaConsumer<String, ShipmentEvent> consumer = new KafkaConsumer<>(props);
```

#### Schema Evolution Strategy

- **Compatibility Mode**: BACKWARD (new schemas can read old data)
- **Versioning**: Semantic versioning (major.minor.patch)
- **Validation**: Automatic validation on schema registration
- **Code Generation**: Maven plugin generates Java classes from Avro schemas

#### Maven Plugin for Avro Code Generation

```xml
<plugin>
  <groupId>org.apache.avro</groupId>
  <artifactId>avro-maven-plugin</artifactId>
  <version>1.12.1</version>
  <executions>
    <execution>
      <phase>generate-sources</phase>
      <goals>
        <goal>schema</goal>
      </goals>
      <configuration>
        <sourceDirectory>${project.basedir}/src/main/avro/</sourceDirectory>
        <outputDirectory>${project.basedir}/target/generated-sources/avro/</outputDirectory>
        <stringType>String</stringType>
      </configuration>
    </execution>
  </executions>
</plugin>
```

---

### 2. Reference Data (Relational Database Tables)

#### Table: `customers`
**Purpose**: Customer master data
**Row Count**: ~200 records

**Schema**:
```sql
CREATE TABLE customers (
  customer_id VARCHAR(50) PRIMARY KEY,
  company_name VARCHAR(200) NOT NULL,
  contact_name VARCHAR(100),
  contact_email VARCHAR(100),
  contact_phone VARCHAR(20),
  address_line1 VARCHAR(200),
  address_line2 VARCHAR(200),
  city VARCHAR(100),
  region VARCHAR(100),
  postal_code VARCHAR(10),
  country VARCHAR(50),
  sla_tier VARCHAR(20), -- STANDARD, PREMIUM, ENTERPRISE
  account_status VARCHAR(20), -- ACTIVE, SUSPENDED, CLOSED
  created_date TIMESTAMP,
  monthly_volume_avg INT
);
```

**Data Characteristics**:
- Mix of small (10 shipments/month), medium (100/month), large (500+/month) customers
- Geographic distribution: 30% Western Europe, 25% Central Europe, 20% Southern Europe, 15% Eastern Europe, 10% Northern Europe
- SLA tiers: 60% STANDARD, 30% PREMIUM, 10% ENTERPRISE

#### Table: `warehouses`
**Purpose**: Warehouse facility information
**Row Count**: 5 records

**Schema**:
```sql
CREATE TABLE warehouses (
  warehouse_id VARCHAR(50) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  address_line1 VARCHAR(200),
  city VARCHAR(100),
  region VARCHAR(100),
  postal_code VARCHAR(10),
  latitude DECIMAL(10, 8),
  longitude DECIMAL(11, 8),
  total_capacity_sqft INT,
  operating_hours VARCHAR(50), -- e.g., "06:00-22:00"
  timezone VARCHAR(50),
  manager_name VARCHAR(100),
  contact_phone VARCHAR(20),
  status VARCHAR(20) -- OPERATIONAL, MAINTENANCE, CLOSED
);
```

**Data**:
- Rotterdam, Netherlands (Western Europe hub)
- Frankfurt, Germany (Central Europe hub)
- Barcelona, Spain (Southern Europe hub)
- Warsaw, Poland (Eastern Europe hub)
- Stockholm, Sweden (Northern Europe hub)

#### Table: `vehicles`
**Purpose**: Fleet vehicle information
**Row Count**: 50 records

**Schema**:
```sql
CREATE TABLE vehicles (
  vehicle_id VARCHAR(50) PRIMARY KEY,
  vehicle_type VARCHAR(50), -- CARGO_VAN, BOX_TRUCK, SEMI_TRAILER
  make VARCHAR(50),
  model VARCHAR(50),
  year INT,
  license_plate VARCHAR(20),
  vin VARCHAR(17),
  capacity_weight_lbs INT,
  capacity_volume_cubic_ft INT,
  home_warehouse_id VARCHAR(50) REFERENCES warehouses(warehouse_id),
  status VARCHAR(20), -- ACTIVE, MAINTENANCE, RETIRED
  last_maintenance_date TIMESTAMP,
  next_maintenance_due_date TIMESTAMP,
  fuel_type VARCHAR(20) -- GASOLINE, DIESEL, ELECTRIC, HYBRID
);
```

**Data Characteristics**:
- 60% cargo vans (capacity: 1,600 kg, 10 m³)
- 30% box trucks (capacity: 4,500 kg, 23 m³)
- 10% semi-trailers (capacity: 20,000 kg, 85 m³)
- Evenly distributed across warehouses

#### Table: `products`
**Purpose**: Product catalog and specifications
**Row Count**: ~10,000 records

**Schema**:
```sql
CREATE TABLE products (
  product_id VARCHAR(50) PRIMARY KEY,
  sku VARCHAR(100) UNIQUE NOT NULL,
  name VARCHAR(200),
  description TEXT,
  category VARCHAR(100),
  subcategory VARCHAR(100),
  weight_lbs DECIMAL(10, 2),
  length_inches DECIMAL(10, 2),
  width_inches DECIMAL(10, 2),
  height_inches DECIMAL(10, 2),
  fragile BOOLEAN,
  hazmat BOOLEAN,
  temperature_controlled BOOLEAN,
  unit_value_usd DECIMAL(10, 2),
  active BOOLEAN
);
```

**Data Characteristics**:
- Categories: Electronics (20%), Apparel (25%), Home Goods (20%), Industrial (15%), Other (20%)
- Weight distribution: 70% light (0-5 lbs), 20% medium (5-25 lbs), 10% heavy (25+ lbs)
- 5% fragile items, 2% hazmat, 3% temperature-controlled

#### Table: `drivers`
**Purpose**: Driver/operator information
**Row Count**: ~75 records

**Schema**:
```sql
CREATE TABLE drivers (
  driver_id VARCHAR(50) PRIMARY KEY,
  first_name VARCHAR(50),
  last_name VARCHAR(50),
  employee_id VARCHAR(50),
  license_number VARCHAR(50),
  license_country VARCHAR(2),
  license_expiry TIMESTAMP,
  cdl_certified BOOLEAN,
  hazmat_certified BOOLEAN,
  home_warehouse_id VARCHAR(50) REFERENCES warehouses(warehouse_id),
  status VARCHAR(20), -- ACTIVE, ON_LEAVE, TERMINATED
  hire_date TIMESTAMP,
  phone VARCHAR(20),
  email VARCHAR(100)
);
```

#### Table: `routes`
**Purpose**: Pre-defined delivery routes and estimates
**Row Count**: ~100 records

**Schema**:
```sql
CREATE TABLE routes (
  route_id VARCHAR(50) PRIMARY KEY,
  origin_warehouse_id VARCHAR(50) REFERENCES warehouses(warehouse_id),
  destination_city VARCHAR(100),
  destination_region VARCHAR(100),
  destination_postal_code VARCHAR(10),
  estimated_duration_minutes INT,
  estimated_distance_miles DECIMAL(10, 2),
  route_type VARCHAR(20), -- LOCAL, REGIONAL, LONG_HAUL
  traffic_pattern VARCHAR(20), -- LOW, MEDIUM, HIGH
  toll_cost_usd DECIMAL(10, 2)
);
```

---

## Kafka Streams Materialized Views

The following state stores will be created by the Kafka Streams application:

### 1. `active-shipments-by-status`
**Type**: KTable (Key-Value Store)
**Key**: `status` (enum)
**Value**: `List<ShipmentSummary>`
**Purpose**: Real-time view of all active shipments grouped by current status

### 2. `vehicle-current-state`
**Type**: KTable (Key-Value Store)
**Key**: `vehicle_id`
**Value**: `VehicleState` (latest telemetry + current load + assigned shipments)
**Purpose**: Current state of all vehicles for fleet monitoring

### 3. `shipments-by-customer`
**Type**: KTable (Key-Value Store)
**Key**: `customer_id`
**Value**: `CustomerShipmentStats` (active count, in-transit, delivered today, late count)
**Purpose**: Customer-specific shipment tracking

### 4. `warehouse-realtime-metrics`
**Type**: Windowed KTable (Tumbling Window: 15 minutes)
**Key**: `warehouse_id`
**Value**: `WarehouseMetrics` (operations count, avg times, error rate)
**Purpose**: Real-time warehouse performance dashboard

### 5. `late-shipments`
**Type**: KTable (Key-Value Store)
**Key**: `shipment_id`
**Value**: `LateShipmentDetails` (customer, SLA, current status, delay minutes)
**Purpose**: Immediate visibility into SLA violations

### 6. `hourly-delivery-performance`
**Type**: Windowed KTable (Hopping Window: 1 hour, advance 15 min)
**Key**: `warehouse_id + hour`
**Value**: `DeliveryStats` (on-time count, late count, avg time, p95 time)
**Purpose**: Rolling hourly performance tracking

---

## Data Generation Implementation Notes

### Event Generation Rates
- **Peak hours** (8 AM - 6 PM local): 100% target rate
- **Off-peak** (6 PM - 10 PM): 40% target rate
- **Night** (10 PM - 6 AM): 10% target rate
- **Weekend**: 30% of weekday rate

### Correlation Rules
1. **Shipment Lifecycle**: Events must follow valid state transitions
2. **Order-Shipment**: Orders reference valid shipments that exist in the stream
3. **Vehicle-Shipment**: Shipments IN_TRANSIT must have valid vehicle assignments
4. **Warehouse-Product**: Products must exist in the product catalog
5. **Geographic Consistency**: Routes, locations, and addresses must be geographically coherent
6. **Temporal Consistency**: Events maintain chronological order per entity

### Data Quality Characteristics
- **Completeness**: 98% of required fields populated
- **Accuracy**: Realistic values within expected ranges
- **Consistency**: Referential integrity across data sources
- **Timeliness**: Real-time streams within 1-5 second latency
- **Anomalies**: Intentional 2-5% anomaly rate for testing exception handling

---

## Sample LLM Agent Queries & Required Data Sources

| Query | Kafka Topics | State Stores | Relational Tables |
|-------|--------------|--------------|-------------------|
| "Which shipments for ACME Corp are delayed?" | shipment.events | late-shipments, shipments-by-customer | customers |
| "Show me vehicles near Rotterdam with capacity" | vehicle.telemetry | vehicle-current-state | warehouses, vehicles |
| "What's order #12345 status and ETA?" | shipment.events, order.status | active-shipments-by-status | routes, customers |
| "Show high-value shipments currently in transit" | shipment.events | active-shipments-by-status | products, customers |

---

## Technology Stack Summary

### Core Technologies
- **Stream Processing**: Apache Kafka 3.7.0, Kafka Streams (Java)
- **Message Format**: Apache Avro 1.11.3
- **Schema Registry**: Apicurio Registry 2.6.0.Final
- **Serialization**: Apicurio Registry Avro Serdes
- **Relational Database**: PostgreSQL 15+
- **Data Generation**: Java-based synthetic data generators
- **Query Interface**: Java application with REST API for LLM agent

### Java Dependencies
- **Kafka Clients**: org.apache.kafka:kafka-clients:3.7.0
- **Kafka Streams**: org.apache.kafka:kafka-streams:3.7.0
- **Avro**: org.apache.avro:avro:1.11.3
- **Apicurio Serdes**: io.apicurio:apicurio-registry-serdes-avro-serde:2.6.0.Final

### Infrastructure Components
- **Kafka Cluster**: 3+ brokers for production, 1 for development
- **Apicurio Registry**: Containerized with PostgreSQL backend
- **PostgreSQL**: For relational data and registry storage

### Geographic Context
- **Region**: Europe
- **Warehouse Locations**: Rotterdam (NL), Frankfurt (DE), Barcelona (ES), Warsaw (PL), Stockholm (SE)
- **Metrics**: Metric system (km, kg, m³)
- **Timezones**: CET, CEST, EET across warehouses

---

## Next Steps

1. **Review & Refine**: Confirm data model meets demonstration requirements
2. **Implementation Plan**: Design data generator architecture
3. **Schema Registration**:
   - Create Avro schema files (.avsc) for all topics
   - Register schemas in Apicurio Registry
   - Generate Java classes from schemas
4. **Database Setup**:
   - Create PostgreSQL relational schema and seed data
   - Set up Apicurio Registry with PostgreSQL backend
5. **Kafka Streams Topology**:
   - Design stream processing logic with Avro Serdes
   - Implement materialized views
6. **LLM Integration**:
   - Design query interface for real-time and operational data
   - Implement multi-source data fusion (Kafka Streams + PostgreSQL)

