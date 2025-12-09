# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## MCP Tools

Always use context7 when I need code generation, setup or configuration steps, or library/API documentation. 
This means you should automatically use the Context7 MCP tools to resolve library id and get library docs without me having to explicitly ask.

## Project Overview

SmartShip Logistics is a real-time event streaming demonstration showcasing Kafka Streams, materialized views, and an LLM-queryable API for a regional logistics and fulfillment company. The project is implemented as a Maven multi-module monorepo deployed on Kubernetes (minikube).

**Current Status:** Phase 1 ✅ COMPLETED | Phase 2 ✅ COMPLETED (4 topics, 4 generators, 6 PostgreSQL tables)

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
1. **Topology** (`LogisticsTopology.java`): Defines stream processing logic and state stores
2. **Interactive Query Server** (`InteractiveQueryServer.java`): Exposes HTTP endpoints on port 7070 for direct state store queries
3. **Query API** (`query-api/`): Quarkus REST service that calls the Interactive Query Server

**Important:** State stores are named constants (e.g., `STATE_STORE_NAME = "active-shipments-by-status"`). Use these constants when adding new query endpoints.

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

# Build Quarkus container (JVM mode for Phase 1)
cd query-api && ./mvnw package -Dquarkus.container-image.build=true && cd ..

# For podman (default):
mvn compile jib:dockerBuild -pl data-generators,streams-processor -Djib.dockerClient.executable=podman

# Load images into minikube (required when using podman)
minikube image load smartship/data-generators:latest
minikube image load smartship/streams-processor:latest
minikube image load smartship/query-api:latest
```

### Testing
```bash
# Run unit tests
mvn test

# Run integration tests
mvn verify

# Test specific module
mvn test -pl streams-processor
```

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

# Get specific status count
curl http://localhost:7070/state/active-shipments-by-status/IN_TRANSIT | jq

# Get all status counts
curl http://localhost:7070/state/active-shipments-by-status | jq

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

### Query via REST API
```bash
kubectl port-forward svc/query-api 8080:8080 -n smartship &

curl http://localhost:8080/api/shipments/by-status/CREATED | jq
curl http://localhost:8080/api/shipments/status/all | jq

# OpenAPI/Swagger UI
open http://localhost:8080/swagger-ui
```

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

### DataCorrelationManager (Singleton)
Central coordinator ensuring referential integrity across all generators:
- Maintains in-memory state matching PostgreSQL seed data
- Tracks active shipments, orders, and vehicle states
- Key methods: `getRandomCustomerId()`, `getRandomVehicleId()`, `registerShipment()`, `getActiveShipmentIdsForWarehouse()`
- File: `data-generators/src/main/java/com/smartship/generators/DataCorrelationManager.java`

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
- 6 PostgreSQL tables with 10,430 total records
- Backward compatible with Phase 1 streams-processor

**Phase 3-6 (PENDING):** See `design/implementation-plan.md` for detailed roadmap:
- Phase 3: Add 5 more Kafka Streams state stores (6 total), consume all 4 topics
- Phase 4: Complete Query API with PostgreSQL hybrid queries
- Phase 5: Native image builds, production hardening
- Phase 6: Demo optimization with LLM integration examples

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