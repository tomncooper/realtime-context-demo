# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## MCP Tools

Always use context7 when I need code generation, setup or configuration steps, or library/API documentation. 
This means you should automatically use the Context7 MCP tools to resolve library id and get library docs without me having to explicitly ask.

## Project Overview

SmartShip Logistics is a real-time event streaming demonstration showcasing Kafka Streams, materialized views, and an LLM-queryable API for a regional logistics and fulfillment company. The project is implemented as a Maven multi-module monorepo deployed on Kubernetes (minikube).

**Current Status:** Phase 1 ✅ COMPLETED | Phase 2 ✅ COMPLETED | Phase 3 ✅ COMPLETED | Phase 4 ✅ COMPLETED | Phase 5 ✅ COMPLETED (9 state stores, 44+ API endpoints, native image, tests)

## Critical Architecture Concepts

### Event Flow Architecture
```
Data Generators → Kafka Topics → Kafka Streams Processor → State Store → Query API
     (Avro)       (4 topics)     (LogisticsTopology)    (Interactive   (Quarkus)
                                                         Queries)

Topics (Phase 2):
├── shipment.events        (50-80 events/sec)
├── vehicle.telemetry      (20-30 events/sec)
├── warehouse.operations   (15-25 events/sec)
└── order.status           (10-15 events/sec)
```

**Key insight:** The system uses **Kafka 4.1.1 with KRaft** (no ZooKeeper) for reduced resource usage (~400Mi memory savings). The Kafka cluster runs as a single-node deployment using Strimzi's `KafkaNodePool` with dual role (controller + broker).

### Module Dependencies
```
schemas → common → {data-generators, streams-processor, query-api}
```

**Build order matters:** Always build `schemas` and `common` first, as they generate Avro classes and provide shared configuration used by all downstream modules.

### Configuration Pattern
All Kafka and Apicurio Registry configuration is centralized in `common/src/main/java/com/smartship/common/KafkaConfig.java`. This class provides factory methods for:
- Producer config with `AvroKafkaSerializer`
- Consumer config with `AvroKafkaDeserializer`
- Streams config with Apicurio Serdes

Environment variables:
- `KAFKA_BOOTSTRAP_SERVERS` (default: `events-cluster-kafka-bootstrap.smartship.svc.cluster.local:9092`)
- `APICURIO_REGISTRY_URL` (default: `http://apicurio-registry.smartship.svc.cluster.local:8080/apis/registry/v2`)

#### Query API Configuration
The Quarkus query-api requires specific configuration in `query-api/src/main/resources/application.properties`:

**Required Quarkus Extensions:**
- `quarkus-rest-jackson` - REST endpoints with JSON
- `quarkus-rest-client-jackson` - REST client for Interactive Queries
- `quarkus-arc` - Dependency injection
- `quarkus-smallrye-openapi` - OpenAPI/Swagger documentation
- `quarkus-smallrye-health` - Health checks for Kubernetes probes
- `quarkus-container-image-jib` - Container image building
- `quarkus-cache` - Instance metadata caching for multi-instance discovery

**Critical Properties:**
```properties
# Java 25 base image for container builds
quarkus.jib.base-jvm-image=eclipse-temurin:25-jdk-ubi10-minimal

# Streams processor connection (multi-instance support)
streams-processor.headless-service=streams-processor-headless.smartship.svc.cluster.local
streams-processor.port=7070

# Instance discovery cache
quarkus.cache.caffeine.streams-instances.expire-after-write=30S
quarkus.cache.caffeine.streams-instances.maximum-size=10
```

### Kafka Streams State Store Pattern
The streams processor (`streams-processor/`) creates materialized KTables that are queryable via Interactive Queries:
1. **Topology** (`LogisticsTopology.java`): Defines stream processing logic and 6 state stores
2. **Interactive Query Server** (`InteractiveQueryServer.java`): Exposes HTTP endpoints on port 7070 for direct state store queries
3. **Query API** (`query-api/`): Quarkus REST service that calls the Interactive Query Server

**Nine State Stores (Phase 4):**
| Store Name | Type | Key | Description |
|------------|------|-----|-------------|
| `active-shipments-by-status` | KeyValue | ShipmentEventType | Count of shipments per status |
| `vehicle-current-state` | KeyValue | vehicle_id | Latest telemetry per vehicle |
| `shipments-by-customer` | KeyValue | customer_id | Aggregated stats per customer |
| `late-shipments` | KeyValue | shipment_id | Shipments past expected delivery |
| `warehouse-realtime-metrics` | Windowed (15 min) | warehouse_id | Operation metrics per window |
| `hourly-delivery-performance` | Windowed (1 hour) | warehouse_id | Delivery stats per window |
| `order-current-state` | KeyValue | order_id | Current state per order (Phase 4) |
| `orders-by-customer` | KeyValue | customer_id | Aggregated order stats per customer (Phase 4) |
| `order-sla-tracking` | KeyValue | order_id | Orders at SLA risk (Phase 4) |

**Important:** State stores are named constants in `LogisticsTopology.java` (e.g., `ACTIVE_SHIPMENTS_BY_STATUS_STORE`). Use these constants when adding new query endpoints.

### Phase 4: Hybrid Query Architecture
The Query API now combines Kafka Streams real-time data with PostgreSQL reference data:

```
Query-API
    ├── KafkaStreamsQueryService → Streams Processor → 9 State Stores
    ├── PostgresQueryService → PostgreSQL → 6 Reference Tables
    └── QueryOrchestrationService → Combines both sources → HybridQueryResult
```

**Key Components:**
- **PostgresQueryService:** Reactive PostgreSQL queries using Mutiny `Uni<T>`
- **QueryOrchestrationService:** Multi-source query orchestration with graceful error handling
- **HybridQueryResult:** Result wrapper with `sources`, `warnings`, and `query_time_ms` metadata
- **ReferenceDataResource:** 17 endpoints for PostgreSQL reference data (`/api/reference/*`)
- **HybridQueryResource:** 7 endpoints for multi-source queries (`/api/hybrid/*`)

### Multi-Instance Streams Processor Architecture
The streams-processor supports horizontal scaling with distributed state store queries:

```
Query-API → Instance Discovery → [Pod-0, Pod-1, Pod-2]
         ↓
    - Specific Key Query → metadataForKey() → Route to Single Pod
    - Aggregate Query → allMetadataForStore() → Query All Pods (parallel) → Merge Results
```

**Key Components:**
- **StatefulSet** (`kubernetes/applications/streams-processor.yaml`): Provides stable network identities for each pod
- **Headless Service** (`streams-processor-headless`): Enables direct pod addressing via DNS
- **StreamsInstanceDiscoveryService** (`query-api/.../services/StreamsInstanceDiscoveryService.java`): Discovers instances via DNS resolution
- **StreamsMetadata Endpoints** (`/metadata/instances/{storeName}`, `/metadata/instance-for-key/{storeName}/{key}`)

**Environment Variables (StatefulSet):**
- `POD_NAME`: Injected from `metadata.name` for stable identity
- `APPLICATION_SERVER`: Set to `$(POD_NAME).streams-processor-headless.smartship.svc.cluster.local:7070`

**Instance Discovery Flow:**
1. Query-API resolves headless service DNS → gets all pod IPs
2. Health checks each instance (`/health` endpoint)
3. Randomly selects from healthy instances for metadata queries
4. Uses `metadataForKey()` to route specific key queries to correct instance
5. Uses parallel queries with `CompletableFuture` for aggregate queries across all instances

## Build Commands

### Initial Setup
```bash
# Build foundation modules first (required before other modules)
mvn clean install -pl schemas,common
```

### Build Individual Modules
```bash
# Build specific module
mvn clean package -pl data-generators
mvn clean package -pl streams-processor

# Build Quarkus query-api (uses wrapper)
cd query-api && ./mvnw clean package && cd ..
```

### Container Images
```bash
# Build with Jib (supports podman/docker via -Djib.dockerClient.executable)
mvn compile jib:dockerBuild -pl data-generators,streams-processor

# Build Quarkus container (JVM mode)
cd query-api && ./mvnw package -Dquarkus.container-image.build=true && cd ..

# For podman (default):
mvn compile jib:dockerBuild -pl data-generators,streams-processor -Djib.dockerClient.executable=podman

# Load images into minikube (required when using podman)
minikube image load smartship/data-generators:latest
minikube image load smartship/streams-processor:latest
minikube image load smartship/query-api:latest
```

### Native Image Build (Phase 5)
```bash
# Build query-api as native image using Python script
python3 scripts/02-build-all.py --native

# Or manually build native image
cd query-api && ./mvnw package -Dnative -Dquarkus.native.container-build=true -Dquarkus.container-image.build=true && cd ..
```

**Native Image Configuration:**
- Builder: `quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21`
- Base: `quay.io/quarkus/quarkus-micro-image:2.0`
- Uses Java 21 for native builds (vs Java 25 for JVM mode)
- Startup time: <100ms (vs ~10s JVM)
- Memory: 64-128Mi (vs 256-512Mi JVM)

### Testing
```bash
# Run unit tests
mvn test

# Run query-api tests specifically
mvn test -pl query-api

# Run integration tests
mvn verify

# Test specific module
mvn test -pl streams-processor

# Run with coverage report
mvn verify -pl query-api
```

**Test Classes (Phase 5):**
- `PostgresQueryServiceTest.java` - PostgreSQL service tests
- `KafkaStreamsQueryServiceTest.java` - Kafka Streams service tests
- `ReferenceDataResourceTest.java` - Reference data endpoint tests
- `QueryResourceTest.java` - Query API endpoint tests
- `HybridQueryResourceTest.java` - Hybrid query endpoint tests

## Deployment (Python Scripts)

**Container Runtime:** Set `CONTAINER_RUNTIME=podman` (default) or `CONTAINER_RUNTIME=docker`

```bash
# Prerequisites: minikube running with adequate resources
minikube start --cpus=4 --memory=12288 --disk-size=50g

# 1. Setup infrastructure (Strimzi, Kafka KRaft, Apicurio, PostgreSQL)
python3 scripts/01-setup-infra.py

# 2. Build all modules and container images
python3 scripts/02-build-all.py

# 3. Deploy applications (data-generators, streams-processor, query-api)
python3 scripts/03-deploy-apps.py

# 4. Validate end-to-end functionality
python3 scripts/04-validate.py

# 5. Cleanup (deletes namespace and Strimzi operator)
python3 scripts/05-cleanup.py
```

**Script Architecture:** All scripts import `scripts/common.py` which provides:
- `kubectl()` - kubectl wrapper
- `wait_for_condition()` - wait for K8s resource readiness (Deployments)
- `wait_for_statefulset_ready()` - wait for StatefulSet pods to be ready
- `setup_container_runtime()` - podman/docker detection
- `get_minikube_env()` - container runtime environment setup
- `verify_kafka_data_flow()` - verify Kafka topic data flow

## Working with Avro Schemas

### Schema Location
`schemas/src/main/avro/*.avsc` → generates Java classes in `schemas/target/generated-sources/avro/`

### After Modifying Schemas
```bash
# Regenerate Java classes
mvn clean install -pl schemas

# Rebuild dependent modules
mvn clean package -pl common,data-generators,streams-processor
```

### Schema Registry Integration
Schemas are auto-registered with Apicurio Registry via `SerdeConfig.AUTO_REGISTER_ARTIFACT = "true"`. Check registered schemas:
```bash
kubectl port-forward svc/apicurio-registry 8080:8080 -n smartship
curl http://localhost:8080/apis/registry/v2/groups/com.smartship.logistics.events/artifacts | jq
```

## Kubernetes Resources

### Kafka Cluster (KRaft - NO ZooKeeper)
The Kafka cluster uses **Strimzi 0.49.0** with KRaft mode:
- `kubernetes/base/kafka-cluster.yaml` defines `KafkaNodePool` (dual-role: controller + broker) + `Kafka` CR
- Kafka version: 4.1.1 with metadataVersion 4.1-IV1
- Single replica for Phase 1 (minikube)
- Pod name: `events-cluster-dual-role-0` (NOT `kafka-cluster-kafka-0`)

**Important:** When debugging Kafka, use the dual-role pod name:
```bash
kubectl exec -it events-cluster-dual-role-0 -n smartship -- bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

### Kustomize Structure
- `kubernetes/infrastructure/` - Core infrastructure (Kafka, Apicurio, PostgreSQL)
  - `init.sql` - PostgreSQL schema (used by `configMapGenerator` to create ConfigMap)
- `kubernetes/applications/` - Application deployments
- `kubernetes/overlays/minikube/` - Laptop-optimized (~1.5 CPU, ~3.5Gi memory)
- `kubernetes/overlays/cloud/` - Cloud-optimized (Phase 5+)

**PostgreSQL Schema:** The `kubernetes/infrastructure/init.sql` file is the single source of truth for the PostgreSQL schema. Kustomize's `configMapGenerator` creates the `postgresql-init` ConfigMap from this file automatically during `kubectl apply`.

Deploy with: `kubectl apply -k kubernetes/overlays/minikube`

## Debugging and Monitoring

### Check Deployment Status
```bash
kubectl get pods -n smartship
# Expected: events-cluster-dual-role-0, apicurio-registry-*, postgresql-0,
#           data-generators-*, streams-processor-0 (StatefulSet), query-api-*

# Check StatefulSet specifically
kubectl get statefulset -n smartship
```

### Monitor Event Generation
```bash
kubectl logs -f deployment/data-generators -n smartship
```

### Query Kafka Streams State Store (Interactive Queries)
```bash
kubectl port-forward svc/streams-processor 7070:7070 -n smartship &

# State Store 1: active-shipments-by-status
curl http://localhost:7070/state/active-shipments-by-status | jq
curl http://localhost:7070/state/active-shipments-by-status/IN_TRANSIT | jq

# State Store 2: vehicle-current-state
curl http://localhost:7070/state/vehicle-current-state | jq
curl http://localhost:7070/state/vehicle-current-state/VH-001 | jq

# State Store 3: shipments-by-customer
curl http://localhost:7070/state/shipments-by-customer | jq
curl http://localhost:7070/state/shipments-by-customer/CUST-001 | jq

# State Store 4: late-shipments
curl http://localhost:7070/state/late-shipments | jq

# State Store 5: warehouse-realtime-metrics (windowed)
curl http://localhost:7070/state/warehouse-realtime-metrics | jq
curl http://localhost:7070/state/warehouse-realtime-metrics/WH-RTM | jq

# State Store 6: hourly-delivery-performance (windowed)
curl http://localhost:7070/state/hourly-delivery-performance | jq
curl http://localhost:7070/state/hourly-delivery-performance/WH-RTM | jq

# Query StreamsMetadata (multi-instance support)
curl http://localhost:7070/metadata/instances/active-shipments-by-status | jq
curl http://localhost:7070/metadata/instance-for-key/active-shipments-by-status/IN_TRANSIT | jq
```

### Validate StatefulSet Configuration
```bash
# Check StatefulSet status
kubectl get statefulset streams-processor -n smartship

# Verify headless service (clusterIP should be None)
kubectl get svc streams-processor-headless -n smartship -o jsonpath='{.spec.clusterIP}'

# Check APPLICATION_SERVER env var in pod
kubectl exec streams-processor-0 -n smartship -- printenv APPLICATION_SERVER

# Scale streams-processor (for multi-instance testing)
kubectl scale statefulset streams-processor -n smartship --replicas=3
```

### Query via REST API (Phase 4: 44+ endpoints)
```bash
kubectl port-forward svc/query-api 8080:8080 -n smartship &

# Shipment endpoints
curl http://localhost:8080/api/shipments/status/all | jq
curl http://localhost:8080/api/shipments/by-status/IN_TRANSIT | jq
curl http://localhost:8080/api/shipments/late | jq

# Vehicle endpoints
curl http://localhost:8080/api/vehicles/state | jq
curl http://localhost:8080/api/vehicles/state/VEH-001 | jq

# Customer endpoints
curl http://localhost:8080/api/customers/shipments/all | jq
curl http://localhost:8080/api/customers/CUST-0001/shipments | jq

# Warehouse metrics endpoints
curl http://localhost:8080/api/warehouses/metrics/all | jq
curl http://localhost:8080/api/warehouses/WH-RTM/metrics | jq

# Performance endpoints
curl http://localhost:8080/api/performance/hourly | jq
curl http://localhost:8080/api/performance/hourly/WH-RTM | jq

# Order endpoints (Phase 4)
curl http://localhost:8080/api/orders/state | jq
curl http://localhost:8080/api/orders/by-customer/all | jq
curl http://localhost:8080/api/orders/sla-risk | jq

# Reference data endpoints (Phase 4 - PostgreSQL)
curl http://localhost:8080/api/reference/warehouses | jq
curl http://localhost:8080/api/reference/customers?limit=10 | jq
curl http://localhost:8080/api/reference/vehicles | jq
curl http://localhost:8080/api/reference/drivers | jq
curl http://localhost:8080/api/reference/routes | jq
curl http://localhost:8080/api/reference/products?limit=5 | jq

# Hybrid query endpoints (Phase 4 - Kafka Streams + PostgreSQL)
curl http://localhost:8080/api/hybrid/customers/CUST-0001/overview | jq
curl http://localhost:8080/api/hybrid/customers/CUST-0001/sla-compliance | jq
curl http://localhost:8080/api/hybrid/vehicles/VEH-001/enriched | jq
curl http://localhost:8080/api/hybrid/drivers/DRV-001/tracking | jq
curl http://localhost:8080/api/hybrid/warehouses/WH-RTM/status | jq
curl http://localhost:8080/api/hybrid/orders/ORD-0001/details | jq

# Health and OpenAPI
curl http://localhost:8080/api/health | jq
open http://localhost:8080/swagger-ui
```

### ID Formats (Critical for Queries)
Use these exact formats when querying:
- **Customers:** `CUST-0001` through `CUST-0200` (4 digits, zero-padded)
- **Vehicles:** `VEH-001` through `VEH-050` (3 digits)
- **Drivers:** `DRV-001` through `DRV-075` (3 digits)
- **Warehouses:** `WH-RTM`, `WH-FRA`, `WH-BCN`, `WH-WAW`, `WH-STO`

### View Kafka Events
```bash
# View shipment events
kubectl exec -it events-cluster-dual-role-0 -n smartship -- \
  bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic shipment.events \
  --from-beginning \
  --max-messages 10

# View vehicle telemetry
kubectl exec -it events-cluster-dual-role-0 -n smartship -- \
  bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic vehicle.telemetry \
  --from-beginning \
  --max-messages 5

# View warehouse operations
kubectl exec -it events-cluster-dual-role-0 -n smartship -- \
  bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic warehouse.operations \
  --from-beginning \
  --max-messages 5

# View order status
kubectl exec -it events-cluster-dual-role-0 -n smartship -- \
  bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic order.status \
  --from-beginning \
  --max-messages 5

# List all topics
kubectl exec -it events-cluster-dual-role-0 -n smartship -- \
  bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

### PostgreSQL Access
```bash
kubectl port-forward svc/postgresql 5432:5432 -n smartship &

# View warehouses
psql -h localhost -U smartship -d smartship -c "SELECT * FROM warehouses;"

# View customers (sample)
psql -h localhost -U smartship -d smartship -c "SELECT * FROM customers LIMIT 10;"

# View all table counts
psql -h localhost -U smartship -d smartship -c "SELECT 'warehouses', COUNT(*) FROM warehouses UNION ALL SELECT 'customers', COUNT(*) FROM customers UNION ALL SELECT 'vehicles', COUNT(*) FROM vehicles UNION ALL SELECT 'products', COUNT(*) FROM products UNION ALL SELECT 'drivers', COUNT(*) FROM drivers UNION ALL SELECT 'routes', COUNT(*) FROM routes;"
```

## Key Technology Versions

**Critical:** These specific versions are required for compatibility:
- **Java:** 25 LTS (eclipse-temurin:25-jdk-ubi10-minimal base image)
- **Kafka:** 4.1.1 (clients + streams)
- **Strimzi:** 0.49.0 (for Kafka 4.1.1 KRaft support)
- **Avro:** 1.12.1
- **Apicurio Registry:** 3.1.4
- **Quarkus:** 3.30.1
- **PostgreSQL:** 15 (postgres:15-alpine)
- **SLF4J:** 2.0.17
- **Logback:** 1.5.12
- **Jib Maven Plugin:** 3.5.1

All versions are centralized in parent `pom.xml` properties.

## Data Generators Architecture (Phase 2)

The `data-generators` module contains 4 generators coordinated by `DataCorrelationManager`:

### Reference Data Loading (Single Source of Truth)
At startup, the data-generators loads all reference data from PostgreSQL:
- **ReferenceDataLoader** connects to PostgreSQL with retry logic (30 attempts, exponential backoff)
- Loads 6 tables: warehouses, customers, vehicles, drivers, products, routes
- Initializes `DataCorrelationManager` with loaded data
- PostgreSQL `kubernetes/infrastructure/init.sql` is the single source of truth

**Environment Variables (data-generators):**
- `POSTGRES_HOST` - PostgreSQL host (default: `postgresql.smartship.svc.cluster.local`)
- `POSTGRES_USER` - Database user (default: `smartship`)
- `POSTGRES_PASSWORD` - Database password
- `POSTGRES_DB` - Database name (default: `smartship`)

### DataCorrelationManager (Singleton)
Central coordinator ensuring referential integrity across all generators:
- Initialized from PostgreSQL data at startup (no hardcoded values)
- Tracks active shipments, orders, and vehicle states
- Key methods: `getRandomCustomerId()`, `getRandomVehicleId()`, `registerShipment()`, `getActiveShipmentIdsForWarehouse()`
- Files:
  - `data-generators/src/main/java/com/smartship/generators/DataCorrelationManager.java`
  - `data-generators/src/main/java/com/smartship/generators/ReferenceDataLoader.java`
  - `data-generators/src/main/java/com/smartship/generators/model/*.java` (Warehouse, Customer, Vehicle, Driver, Product, Route, ReferenceData)

### Four Generator Threads
All started by `GeneratorMain.java`:

| Generator | Topic | Rate | Key Features |
|-----------|-------|------|--------------|
| ShipmentEventGenerator | shipment.events | 50-80/sec | 9-state lifecycle, 5% exception, 2% cancelled |
| VehicleTelemetryGenerator | vehicle.telemetry | 20-30/sec | 50 vehicles, position updates, fuel consumption |
| WarehouseOperationGenerator | warehouse.operations | 15-25/sec | 7 operation types, 3% error rate |
| OrderStatusGenerator | order.status | 10-15/sec | 4 SLA tiers, 1-3 shipments per order |

## Avro Schemas (Phase 2)

Four schemas in `schemas/src/main/avro/`:

| Schema | Key Fields | Enums |
|--------|------------|-------|
| `shipment-event.avsc` | shipment_id, customer_id, warehouse_id, destination_city | ShipmentEventType (9 values) |
| `vehicle-telemetry.avsc` | vehicle_id, location (nested), current_load (nested) | VehicleStatus (5 values) |
| `warehouse-operation.avsc` | event_id, warehouse_id, operation_type, shipment_id (nullable) | OperationType (7 values) |
| `order-status.avsc` | order_id, customer_id, shipment_ids (array), priority | OrderStatusType (7), OrderPriority (4) |

## PostgreSQL Reference Data (Phase 2)

Six tables with full-scale seed data in `kubernetes/infrastructure/init.sql`:

| Table | Records | Key Columns |
|-------|---------|-------------|
| warehouses | 5 | warehouse_id, city, country, capacity |
| customers | 200 | customer_id, company_name, sla_tier |
| vehicles | 50 | vehicle_id, vehicle_type, capacity_kg, home_warehouse_id |
| products | 10,000 | product_id, sku, category, weight_kg |
| drivers | 75 | driver_id, name, license_type, assigned_vehicle_id |
| routes | 100 | route_id, origin_warehouse_id, destination_city, distance_km |

Query all table counts:
```bash
kubectl exec -it statefulset/postgresql -n smartship -- psql -U smartship -d smartship \
  -c "SELECT 'warehouses', COUNT(*) FROM warehouses UNION ALL SELECT 'customers', COUNT(*) FROM customers UNION ALL SELECT 'vehicles', COUNT(*) FROM vehicles UNION ALL SELECT 'products', COUNT(*) FROM products UNION ALL SELECT 'drivers', COUNT(*) FROM drivers UNION ALL SELECT 'routes', COUNT(*) FROM routes;"
```

## Adding New Event Types (Future Phases)

When implementing additional topics and generators:

1. **Create Avro schema** in `schemas/src/main/avro/`
2. **Add topic definition** in `kubernetes/infrastructure/kafka-topic.yaml` (KafkaTopic CR)
3. **Create generator** in `data-generators/src/main/java/com/smartship/generators/`
4. **Register with DataCorrelationManager** if referential integrity needed
5. **Extend LogisticsTopology** in `streams-processor/` with new state stores
6. **Add Interactive Query endpoints** in `InteractiveQueryServer.java`
7. **Add REST endpoints** in `query-api/src/main/java/com/smartship/api/`
8. **Rebuild in order:** schemas → common → other modules

## Phase Information

**Phase 1 (✅ COMPLETED):** Minimal end-to-end with 1 topic, 1 state store
- Topic: `shipment.events` (CREATED → IN_TRANSIT → DELIVERED)
- State store: `active-shipments-by-status` (count by status)
- Generator: Simple lifecycle every 6 seconds
- Multi-instance streams-processor support with StatefulSet

**Phase 2 (✅ COMPLETED):** All 4 topics producing events with full-scale reference data
- 4 Avro schemas (1 expanded + 3 new with nested records)
- 4 Kafka topics at specified event rates
- 4 generators + DataCorrelationManager for referential integrity
- 6 PostgreSQL tables with 10,430 total records (single source of truth)
- ReferenceDataLoader loads all reference data from PostgreSQL at startup
- Backward compatible with Phase 1 streams-processor

**Phase 3 (✅ COMPLETED):** All 6 Kafka Streams state stores operational
- 6 state stores consuming 3 topics (shipment.events, vehicle.telemetry, warehouse.operations)
- 4 KeyValue stores: active-shipments-by-status, vehicle-current-state, shipments-by-customer, late-shipments
- 2 Windowed stores: warehouse-realtime-metrics (15-min), hourly-delivery-performance (1-hour hopping)
- 14 REST API endpoints across 5 resource groups (Shipments, Vehicles, Customers, Warehouses, Performance)
- JsonSerde for custom state store value serialization
- Multi-instance query support with parallel aggregation

**Phase 4 (✅ COMPLETED):** Full LLM query capability with multi-source queries
- 9 state stores (6 original + 3 order stores consuming order.status topic)
- PostgreSQL reference data integration via Quarkus reactive PostgreSQL client
- 17 reference data endpoints (`/api/reference/*`)
- 7 hybrid query endpoints (`/api/hybrid/*`) combining Kafka Streams + PostgreSQL
- QueryOrchestrationService for multi-source query orchestration
- HybridQueryResult with `warnings` field for graceful error handling
- Correct ID formats: CUST-0001 (4 digits), VEH-001 (3 digits), DRV-001 (3 digits)

**Phase 5 (✅ COMPLETED):** Native image builds, testing, and production hardening
- GraalVM/Mandrel native image compilation for query-api
- 5 test classes with JUnit 5, Mockito, Rest-Assured, JaCoCo
- Exception handling with consistent JSON error responses
- NativeImageReflectionConfig registering 23 model classes
- Build script `--native` flag support (`python3 scripts/02-build-all.py --native`)
- Native image: <100ms startup, 64-128Mi memory, ~50MB container size
- Key files:
  - `query-api/src/main/java/com/smartship/api/config/NativeImageReflectionConfig.java`
  - `query-api/src/main/java/com/smartship/api/config/ExceptionMappers.java`
  - `query-api/src/test/java/com/smartship/api/**/*Test.java` (5 test classes)

**Phase 6-8 (PENDING):** See `design/implementation-plan.md` for detailed roadmap:
- Phase 6: Demo optimization with Grafana dashboards
- Phase 7: LLM chatbot integration with Quarkus LangChain4j and Ollama
- Phase 8: Advanced LLM features with guardrails and analytics

## Common Issues

### Images Not Found in Minikube
When using podman, images must be explicitly loaded:
```bash
minikube image load smartship/data-generators:latest
minikube image load smartship/streams-processor:latest
minikube image load smartship/query-api:latest
```

### Kafka Cluster Not Ready
Check Strimzi operator logs:
```bash
kubectl logs deployment/strimzi-cluster-operator -n smartship
```

Verify Kafka status:
```bash
kubectl get kafka events-cluster -n smartship -o yaml
```

### State Store Empty or Null Values
The Kafka Streams processor needs time to consume events and populate state stores. Wait 10-30 seconds after deployment, then check:
```bash
kubectl logs statefulset/streams-processor -n smartship | grep "Updated count"
```

### Avro Serialization Errors
Ensure Apicurio Registry is running and accessible:
```bash
kubectl get pods -n smartship | grep apicurio
kubectl logs deployment/apicurio-registry -n smartship
```

Check that environment variables are set correctly in deployments (APICURIO_REGISTRY_URL).

### Query API Health Check Failures
The query-api requires the `quarkus-smallrye-health` dependency for Kubernetes liveness and readiness probes:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-health</artifactId>
</dependency>
```

Additionally, the Quarkus container image must use Java 25 base image. Set in `query-api/src/main/resources/application.properties`:
```properties
quarkus.jib.base-jvm-image=eclipse-temurin:25-jdk-ubi10-minimal
```

Without these, pods will fail health checks with HTTP 404 or UnsupportedClassVersionError.