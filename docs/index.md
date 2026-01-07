# Real-time Context Demo

Source Code Repository: [https://github.com/tomncooper/realtime-context-demo](https://github.com/tomncooper/realtime-context-demo)

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
Customers issue orders, which contain one or more shipments, via the order management system.

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
- shipment.events: This is similar to the order status topic but for the individual shipments (packages) which make up an order. Messages on this topic include the customer and shipment IDs, source and destination information and the status of the shipment:
    - `CREATED`
    - `PICKED`
    - `PACKED`
    - `DISPATCHED`
    - `IN_TRANSIT`
    - `OUT_FOR_DELIVERY`
    - `DELIVERED`
    - `EXCEPTION`
    - `CANCELLED`
- vehicle.telemetry: Each vehicle updates head office at least once a minute. They provide location, speed and heading information as well as the current vehicle status:
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

Each topic has an associated Avro message schema (you can see these in the `schemas` module in the demo repository) and these are registered in an [Apicurio Registry](https://www.apicur.io/registry/) instance.

## Real-time State

The data in these four topics represents the current state of the SmartShip business. 
Downstream of these topics, many different business processes can be attached. 
For critical business operations the shipment and order events will be persisted in a relational database.
However, the people in charge of SmartShip know that this data has more to offer than just facilitating the day-to-day operation of the company.
For example the vehicle telemetry could be loaded into a timeseries database for visualisation and routing analytics.
The orders and shipment events could be loaded into Apache Iceberg tables to allow for analysis of customer purchasing trends.
However, those analytical requirements mean running additional databases and datalake systems, their associated infrastructure and the tooling to extract and load the events into them.
What if we would like to see the state of the streams with less infrastructure and crucially less latency?

One option for this is using Kafka Streams and its [Interactive Queries](https://kafka.apache.org/41/streams/developer-guide/interactive-queries/) functionality.
This allows users to write relatively simple Kafka Streams queries that materialize a stream of data as a table-like structure. 
This table can then be exposed via a server and the value of specific keys, or ranges of keys, queried in real time.

### Kafka Streams Materialized Views

Kafka Streams provides a powerful abstraction called a [KTable](https://kafka.apache.org/41/javadoc/org/apache/kafka/streams/kstream/KTable.html), which represents a changelog stream as a table.
Each record in the underlying stream represents an update to the table, and the KTable maintains the latest value for each key.
When combined with state stores, KTables become queryable materialized views of your streaming data.

For example, to answer the question "How many shipments are currently in each of the possible shipment states?", SmartShip can create a KTable that reads the `shipment.events` topic and counts shipments grouped by their ShipmentEventType:

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
For more complex queries, aggregations can build custom objects. 
For instance, to track per-customer shipment statistics, an aggregate function can maintain a running total of shipments, deliveries, and exceptions for each customer.

### Kafka Streams Interactive Queries

Once data is materialized into state stores, Kafka Streams' Interactive Queries feature allows these stores to be queried directly without needing to export the data to an external database.
This provides low query latency on the current state of your streams.

However, Interactive Queries introduce a complication when using a Kafka Streams deployment which has been scaled out (multiple running instances of the Kafka Streams application). 
Kafka Streams distributes the partitions of the application's input topic(s) across multiple application instances, which means any given key's state might reside on a different instance than the one receiving the query.
If you have three streams-processor instances, querying for `VEH-001`'s current state requires knowing which instance holds that vehicle's partition.

Kafka Streams provides functionality to help developers solve this issue. 
Each instance of a streams application can set a `application.server` configuration values which specifies a `host:port` address for that instance.
Kafka Streams applications can then call methods like [`metadataForAllStreamsClients`](https://kafka.apache.org/41/javadoc/org/apache/kafka/streams/KafkaStreams.html#metadataForAllStreamsClients()) on the `KafkaStreams` instance to receive a [`StreamsMetadata`](https://kafka.apache.org/41/javadoc/org/apache/kafka/streams/StreamsMetadata.html) object for each application instance.
This allows an overall view of the state store and partition distribution across the application.
For a more direct query, applications can call [`queryMetadataForKey`](https://kafka.apache.org/41/javadoc/org/apache/kafka/streams/KafkaStreams.html#queryMetadataForKey(java.lang.String,K,org.apache.kafka.common.serialization.Serializer)) to receive specific information on where a given key is located.

While this functionality allows a streams application to locate which instance a given key is on, that is only part of the story. 
You still need to be able to route queries to the appropriate store.
For individual keys this is relatively straight forward, you can query any application instance for the key's location and once found issue the request to that instance. 
However, you often need to query a range of keys and so you need to implement a system that can handle aggregating the various look-up requests.

So to provide a production ready Kafka Streams based interactive query system, you need to provide application side RPC endpoints for discovering metadata and serving key and/or key range requests. 
You then also need somewhere to host the logic for discovering, querying and aggregating the data from the distributed state stores.
This logic could be in a library used by the querying entity or could be hosted in a central service which querying entities then call.

## Implementing the Interactive Queries

In this project we have opted for providing a centralised query services which handles the distributed query logic and provides a single REST API for querying the Kafka Streams instances.
There are two key parts of this architecture:

![Interactive Queries Architecture](assets/imgs/interactive-queries.png)

1. **Streams Processor (Interactive Query Server)**: `streams-processor/src/main/java/com/smartship/streams/InteractiveQueryServer.java`
   
   Each instance of the Stream Processor application hosts an Interactive Query Server instance which implements endpoints for each of the state stores.
   They also each provide a metadata endpoint which the Query Gateway can use to discover key locations:

   - `/metadata/instances/{storeName}` - Lists all instances hosting a store
   - `/metadata/instance-for-key/{storeName}/{key}` - Finds the specific instance for a key

2. **Query API (Gateway)**: `query-api/src/main/java/com/smartship/api/KafkaStreamsQueryService.java`

   A stateless Quarkus-based service that:

   - Discovers all streams-processor instances via Kubernetes headless service DNS
   - Health-checks each instance to build a list of available nodes
   - Routes key-specific queries to the correct instance using metadata lookups
   - Aggregates results across all instances for queries that span multiple partitions

This architecture allows the Query API to provide a unified interface to clients while the streams-processor instances scale horizontally, each managing its portion of the partitioned state.

### An example query

To show the process end-to-end, let's walk through how the system answers the question: **"How many shipments are currently in each status?"**

This query requires aggregating data from multiple partitions that may be distributed across different streams-processor instances, making it a good example of how the interactive query architecture handles distributed state.

#### Step 1: Creating the Materialized View (KTable)

The `LogisticsTopology` class creates a materialized view by consuming the `shipment.events` topic and counting shipments by their status:

```java
KTable<String, Long> shipmentCountsByStatus = shipmentStream
    .groupBy(
        (_, value) -> value.getEventType().toString(),
        Grouped.with(Serdes.String(), shipmentEventSerde)
    )
    .count(
        Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("active-shipments-by-status")
            .withKeySerde(Serdes.String())
            .withValueSerde(Serdes.Long())
    );
```

This code:

1. Re-keys each shipment event by its status (e.g., `IN_TRANSIT`, `DELIVERED`, `EXCEPTION`)
2. Groups all events with the same status key together
3. Counts the number of events per status
4. Materializes the result into a named state store called `active-shipments-by-status`

As new shipment events arrive, the counts are automatically updated in real-time.

#### Step 2: Interactive Query Server Endpoints

Each streams-processor instance runs an `InteractiveQueryServer` that exposes HTTP endpoints for querying its local state store partition.
This includes an endpoint for returning the current state of the `active-shipments-by-status` store: 

```java
server.createContext("/state/active-shipments-by-status", this::handleActiveShipmentsByStatus);
```

```java
private void handleActiveShipmentsByStatus(HttpExchange exchange) throws IOException {
    
    ...

    ReadOnlyKeyValueStore<String, Long> store = streams.store(
            StoreQueryParameters.fromNameAndType(
                    "active-shipments-by-status",
                    QueryableStoreTypes.keyValueStore()
            )
    );

    // Query all statuses from this instance's partition
    Map<String, Long> counts = new LinkedHashMap<>();
    for (ShipmentEventType status : ShipmentEventType.values()) {
        Long count = store.get(status.name());
        counts.put(status.name(), count != null ? count : 0L);
    }
    sendJsonResponse(exchange, counts); 
    
    ...
}
```

The server also provides metadata endpoints that the Query API uses to discover key locations:

- `GET /metadata/instances/{storeName}` - Returns all instances hosting partitions of a store
- `GET /metadata/instance-for-key/{storeName}/{key}` - Finds which instance holds a specific key

#### Step 3: Query API Gateway Aggregation

The Query API acts as a gateway that coordinates queries across all streams-processor instances. 
First, it discovers healthy instances via DNS (see `query-api/src/main/java/com/smartship/api/services/StreamsInstanceDiscoveryService.java`):

```java
// Resolve all IPs for the headless Kubernetes service
InetAddress[] addresses = InetAddress.getAllByName(headlessService);

for (InetAddress address : addresses) {
    String instanceUrl = UriBuilder.newInstance()
        .scheme("http")
        .host(address.getHostAddress())
        .port(port)
        .build()
        .toString();

    if (isInstanceHealthy(instanceUrl)) {
        healthyInstances.add(instanceUrl);
    }
}
```

Then it queries all instances in parallel and merges the results (see `getAllStatusCounts` in `query-api/src/main/java/com/smartship/api/KafkaStreamsQueryService.java`):

```java
// Create parallel futures to query each instance
List<CompletableFuture<Map<String, Long>>> futures = instances.stream()
    .map(instance -> CompletableFuture.supplyAsync(
        () -> queryJsonNumberMap(instance, "/state/active-shipments-by-status"),
        executorService
    ))
    .collect(Collectors.toList());

// Wait for all queries to complete
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

// Merge counts from all instances (sum values for each status)
Map<String, Long> mergedCounts = new HashMap<>();
for (CompletableFuture<Map<String, Long>> future : futures) {
    Map<String, Long> instanceCounts = future.get();
    instanceCounts.forEach((status, count) ->
        mergedCounts.merge(status, count, Long::sum)
    );
}
```

#### Query Flow Diagram

The following diagram illustrates the complete flow when a client requests shipment counts by status:

![Query Flow Diagram](assets/imgs/query-flow.png)

The key insight here is that because the state store is partitioned by key across instances, no single instance has the complete picture.
The Query API must aggregate results from all instances to provide an accurate answer.
For queries that target a specific key (e.g., "What is the count for IN_TRANSIT?"), the Query API can use the metadata endpoint to route the request directly to the instance holding that key's partition.

### Other queries

The SmartShip streams-processor maintains nine state stores in total, each designed to answer specific business questions:

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

## Hybrid Queries

While streaming state stores are good at providing real-time aggregations, they don't contain all the context needed to answer complex business questions.
For example, knowing that `VEH-001` is currently at coordinates (52.5200, 13.4050) is useful, but an operator also needs to know the driver's name, the vehicle's capacity, and its home warehouse.

The system addresses this by combining Kafka Streams state stores with PostgreSQL reference data through hybrid queries.
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

The Query API exposes hybrid queries through endpoints like:

- `GET /api/hybrid/customers/{id}/overview` - Customer details with real-time order and shipment stats
- `GET /api/hybrid/vehicles/{id}/enriched` - Vehicle reference data with current telemetry
- `GET /api/hybrid/warehouses/{id}/status` - Warehouse details with real-time operational metrics
- `GET /api/hybrid/orders/{id}/details` - Order information with customer and shipment context

