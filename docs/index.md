# Real-time Context Demo

## Introduction

Systems like [Apache Kafka](https://kafka.apache.org/) allow organisations to develop event driven architectures, which allow businesses to react to changes in real time. 
While these systems provide a flexible way to implement new business processes on top of low latency data streams, it can often be difficult to analyse the state of those streams and get a clear picture of the data as it exists _now_.
Typically, companies will load the streams from Kafka topics into an analytical database, like Google Big Table or a datalake based on Open Table Formats like Apache Iceberg, and query the data from there.
Alternatively, if it is a common query, they might create specific extract-transform-load (ETL) jobs using Kafka client libraries in bespoke microservices or create a dedicated stream processing job using systems like Apache Flink.
This process can be quite involved and requires time to implement and tune. 
A simpler alternative can be to create materialized versions of key data streams using Kafka Streams tables and exposing their state stores.
This allows the current state of the streams to be queried by various tools, including AI agents.

Agents (be they based on large language models or humans writing queries) work best with the most up-to-date context. 
Using the latest materialized state of data streams composed of key business events, provides a way to get the most relevant up-to-date data to agents so they can answer queries as accurately as possible.

This walk-through describes an example business use case for materialized streams and how these can be configured to be accessible by an AI agent to answer queries about the current state of the business.

## SmartShip

The use case in the walk-through is based on the fictional company SmartShip, which is a logistics and fulfilment company based in Europe.
The company employees 75 drivers, operating 50 vehicles, out of 5 warehouse spread across several countries in Europe.
They deliver over 10,000 products to 200 customers.

SmartShip has implemented an event driven architecture. 
All the vehicles report their locations back to head office at least every minute, the warehouses are fully instrumented and each shipment's status is updated in real-time.
Customers issue orders, which can contain multiple shipments, via the order management system.

![SmartShip Architecture](assets/imgs/architecture.png)

There are 4 key Kafka topics in the system:

- order.status: Every time the status of a customer order changes, a message is posted to this topic containing the customer, order and shipment IDs and the new status of the order:
    - `RECEIVED`
    - `VALIDATED`
    - `ALLOCATED`
    - `SHIPPED`
    - `DELIVERED`
    - `CANCELLED`
    - `RETURNED`
    - `PARTIAL_FAILURE`
    - The state of an order only changes once all the shipments within it have moved to the new state.
- shipment.events: This is similar to the order status topic but for individual shipments (packages). Messages on this topic include the customer and shipment IDs, source and destination information and the status of the shipment:
    - `CREATED`
    - `PICKED`
    - `PACKED`
    - `DISPATCHED`
    - `IN_TRANSIT`
    - `OUT_FOR_DELIVERY`
    - `DELIVERED`
    - `EXCEPTION`
    - `CANCELLED`
- vehicle.telemetry: Each vehicle updates head office at least once a minuet. They provide location, speed and heading information as well as the current vehicle status:
    - `IDLE`
    - `EN_ROUTE`
    - `LOADING` 
    - `UNLOADING`
    - `MAINTENANCE`
- warehouse.ops: Each warehouse is instrumented to report operations on shipments. The messages in this topic include the warehouse location, product and shipment IDs and the type of operation being performed which include:
    - `RECEIVING`
    - `PUTAWAY`
    - `PICK`
    - `PACK`
    - `LOAD`
    - `INVENTORY_ADJUSTMENT`
    - `CYCLE_COUNT`

Each topic has an associated Avro message schema (you can see these in the `schemas` module in the demo repository) and these are registered in an Apicurio Registry instance.

## Real-time State

The data in these four topics represents the current state of the SmartShip business. 
Downstream of these topics many different business functions can be attached. 
For critical business operations the shipment and order events will be persisted in a relational database.
However, the people in charge of SmartShip know that this data has more to offer than just facilitating the day-to-day operations.
For example the vehicle telemetry could be loaded into a timeseries database for visualisation and routing analytics.
The orders and shipment events could be loaded into Apache Iceberg tables for analysis of customer trends.
However, those analytical requirements mean running additional databases and datalake systems, their associated infrastructure and the tooling to extract and load the events into them.
What if we would like to see the state of the streams with less infrastructure and crucially less latency?

One option for this is using Kafka Streams and its [Interactive Queries](https://kafka.apache.org/41/streams/developer-guide/interactive-queries/) functionality.
This allows users to write relatively simple Kafka Streams queries that materialize a stream of data as a table-like structure. 
This table can then be exposed via a server and value of specific keys, or ranges or key, queried in real time.

### Kafka Streams Materialized Views

Kafka Streams provides a powerful abstraction called a KTable, which represents a changelog stream as a table.
Each record in the underlying stream represents an update to the table, and the KTable maintains the latest value for each key.
When combined with state stores, KTables become queryable materialized views of your streaming data.

For example, to answer the question "How many shipments are currently in each status?", SmartShip creates a KTable that counts shipments grouped by their status:

```java
KTable<String, Long> shipmentCountsByStatus = shipmentStream
    .groupBy(
        (key, value) -> value.getEventType().toString(),
        Grouped.with(Serdes.String(), shipmentEventSerde)
    )
    .count(
        Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("active-shipments-by-status")
            .withKeySerde(Serdes.String())
            .withValueSerde(Serdes.Long())
    );
```

This code:

1. Reads from the `shipment.events` stream
2. Re-keys each event by its status (e.g., `IN_TRANSIT`, `DELIVERED`)
3. Counts the events per key
4. Materializes the result into a named state store

The state store is automatically updated as new events arrive, providing a real-time count of shipments in each status that can be queried at any time.
For more complex queries, aggregations can build custom objects. For instance, to track per-customer shipment statistics, an aggregate function can maintain a running total of shipments, deliveries, and exceptions for each customer.

### Kafka Streams Interactive Queries

Once data is materialized into state stores, Kafka Streams' Interactive Queries feature allows these stores to be queried directly without needing to export the data to an external database.
This provides sub-millisecond query latency on the current state of your streams.

However, Interactive Queries introduce a complication in distributed deployments.
Kafka Streams partitions data across multiple application instances, which means any given key's state might reside on a different instance than the one receiving the query.
If you have three streams-processor instances, querying for `VEH-001`'s current state requires knowing which instance holds that vehicle's partition.

SmartShip solves this challenge with a two-layer architecture:

1. **Streams Processor (Interactive Query Server)**: Each instance exposes its local state stores via HTTP endpoints on port 7070. It also provides metadata endpoints that reveal which instances host which keys:

   - `/metadata/instances/{storeName}` - Lists all instances hosting a store
   - `/metadata/instance-for-key/{storeName}/{key}` - Finds the specific instance for a key

2. **Query API (Gateway)**: A stateless Quarkus service that:

   - Discovers all streams-processor instances via Kubernetes headless service DNS
   - Health-checks each instance to build a list of available nodes
   - Routes key-specific queries to the correct instance using metadata lookups
   - Aggregates results across all instances for queries that span multiple partitions

This architecture allows the Query API to provide a unified interface to clients while the streams-processor instances scale horizontally, each managing its portion of the partitioned state.

### Example queries

The SmartShip streams-processor maintains nine state stores, each designed to answer specific business questions:

**Shipment Tracking**

| State Store | Question Answered |
|------------|-------------------|
| `active-shipments-by-status` | How many shipments are in each status right now? |
| `shipments-by-customer` | What are the shipment statistics for a specific customer? |
| `late-shipments` | Which shipments are past their expected delivery time? |

**Fleet Management**

| State Store | Question Answered |
|------------|-------------------|
| `vehicle-current-state` | Where is a specific vehicle and what is it doing? |

**Warehouse Operations**

| State Store | Question Answered |
|------------|-------------------|
| `warehouse-realtime-metrics` | What operations have occurred at each warehouse in the last 15 minutes? |
| `hourly-delivery-performance` | What is the delivery performance for each warehouse over the past hour? |

**Order Management**

| State Store | Question Answered |
|------------|-------------------|
| `order-current-state` | What is the current status of a specific order? |
| `orders-by-customer` | What are the order statistics for a specific customer? |
| `order-sla-tracking` | Which orders are at risk of breaching their SLA? |

Each state store uses an appropriate Kafka Streams pattern:

- **Counting stores** (like `active-shipments-by-status`) use `.count()` aggregations
- **Latest-value stores** (like `vehicle-current-state`) use `.reduce()` to keep only the most recent event
- **Aggregating stores** (like `shipments-by-customer`) use `.aggregate()` with custom accumulator objects
- **Windowed stores** (like `warehouse-realtime-metrics`) use time-based windows to provide metrics over specific time periods

These stores are exposed via the Query API with REST endpoints such as:

- `GET /api/shipments/status/all` - Returns counts for all shipment statuses
- `GET /api/vehicles/state/VEH-001` - Returns current state of vehicle VEH-001
- `GET /api/customers/CUST-0001/shipments` - Returns shipment stats for customer CUST-0001
- `GET /api/orders/sla-risk` - Returns all orders at risk of SLA breach

### Hybrid Queries

While streaming state stores excel at providing real-time aggregations, they don't contain all the context needed to answer complex business questions.
For example, knowing that `VEH-001` is currently at coordinates (52.5200, 13.4050) is useful, but an operator also needs to know the driver's name, the vehicle's capacity, and its home warehouse.

SmartShip addresses this by combining Kafka Streams state stores with PostgreSQL reference data through hybrid queries.
The PostgreSQL database contains relatively static reference data across six tables: warehouses, customers, vehicles, drivers, products, and routes.
The Query API's `QueryOrchestrationService` coordinates queries across both data sources and merges the results.

For instance, the "enriched vehicle state" hybrid query:

1. Fetches the vehicle's reference data from PostgreSQL (type, capacity, home warehouse)
2. Retrieves the vehicle's real-time telemetry from the Kafka Streams state store (location, speed, status)
3. Looks up the assigned driver from PostgreSQL
4. Combines all data into a single response with a human-readable summary

```json
{
  "sources": ["postgresql", "kafka-streams"],
  "query_time_ms": 45,
  "data": {
    "vehicle": { "vehicle_id": "VEH-001", "type": "Van", "capacity_kg": 1500 },
    "current_state": { "status": "EN_ROUTE", "speed": 65.5, "location": {...} },
    "driver": { "name": "Jan Kowalski", "license_type": "C" }
  },
  "summary": "Vehicle VEH-001 (Van, 1500kg capacity) is EN_ROUTE at 65.5 km/h, driven by Jan Kowalski"
}
```

Hybrid queries handle failures gracefully.
If the Kafka Streams data is temporarily unavailable, the query still returns the PostgreSQL reference data along with a warning, rather than failing entirely.
This resilience ensures that operators always get the best available information, even during partial system degradation.

The Query API exposes hybrid queries through endpoints like:

- `GET /api/hybrid/customers/{id}/overview` - Customer details with real-time order and shipment stats
- `GET /api/hybrid/vehicles/{id}/enriched` - Vehicle reference data with current telemetry
- `GET /api/hybrid/warehouses/{id}/status` - Warehouse details with real-time operational metrics
- `GET /api/hybrid/orders/{id}/details` - Order information with customer and shipment context

