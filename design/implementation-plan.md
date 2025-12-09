# SmartShip Logistics Implementation Plan

**Status:** Phase 1 ✅ COMPLETED | Phase 2-6 Pending

## Overview

This plan implements the synthetic data proposal for a regional logistics and fulfillment company with real-time event streaming, materialized views, and an LLM-queryable API.

**Target Architecture:**
- Apache Kafka 4.1.1 with KRaft (via Strimzi Operator 0.49.0) - NO ZooKeeper
- Apicurio Registry 3.1.4 for Avro schema management
- Kafka Streams 4.1.1 for real-time materialized views
- PostgreSQL 15 (postgres:15-alpine) for reference data
- Quarkus 3.30.1 REST API for LLM queries (JVM mode in Phase 1, native image in Phase 5)
- Java 25 LTS (eclipse-temurin:25-jdk-ubi10-minimal base image)
- All components running on Kubernetes (minikube for Phase 1, cloud for later phases)
- Python 3.9+ deployment automation with podman/docker support

## Project Structure: Maven Monorepo

**Decision:** Monorepo with Maven multi-module for centralized schema management and unified builds.

```
realtime-context-demo/
├── pom.xml                          # Parent POM (✅ Phase 1)
├── schemas/                         # Avro schemas → generates Java classes (✅ Phase 1: shipment-event.avsc)
├── common/                          # Shared utilities (✅ Phase 1: KafkaConfig, ApicurioConfig)
├── data-generators/                 # Synthetic event producers (✅ Phase 1: 1 generator, Phase 2-6: 4 generators)
├── streams-processor/               # Kafka Streams (✅ Phase 1: 1 state store, Phase 3: 6 state stores)
├── query-api/                       # Quarkus REST API (✅ Phase 1: JVM mode, Phase 5: native image)
├── kubernetes/
│   ├── infrastructure/              # Infrastructure resources (✅ Phase 1)
│   │   └── init.sql                 # PostgreSQL DDL and seed data (✅ Phase 1: warehouses table)
│   └── overlays/
│       ├── minikube/                # Laptop-friendly (✅ Phase 1: ~1.5 CPU, ~3.5Gi RAM with KRaft)
│       └── cloud/                   # Cloud-optimized (auto-scaling) - Phase 5
├── scripts/                         # Python deployment automation (✅ Phase 1)
│   ├── common.py
│   ├── 01-setup-infra.py
│   ├── 02-build-all.py
│   ├── 03-deploy-apps.py
│   ├── 04-validate.py
│   └── 05-cleanup.py
└── design/                          # Design documentation (✅)
    ├── implementation-plan.md
    └── synthetic-data-proposal.md
```

## Component Architecture

### 1. Avro Schemas Module
**Purpose:** Centralized schema definitions for all 4 Kafka topics

**Schemas to create:**
- `schemas/src/main/avro/shipment-event.avsc` - Shipment lifecycle events
- `schemas/src/main/avro/vehicle-telemetry.avsc` - Vehicle location/status
- `schemas/src/main/avro/warehouse-operation.avsc` - Warehouse operations
- `schemas/src/main/avro/order-status.avsc` - Order status changes

**Build:** Maven avro-maven-plugin generates Java classes → `target/generated-sources/avro/`

### 2. Common Module
**Purpose:** Shared code for all components

**Key classes:**
- `KafkaConfig` - Producer/consumer factory with Apicurio Serdes
- `ApicurioConfig` - Schema registry configuration
- `GeoUtils` - Route simulation, distance calculations for European cities
- `TimeUtils` - European timezone handling (CET, CEST, EET)
- Data models: `Customer`, `Warehouse`, `Vehicle`, `Product`, `Driver`, `Route`

### 3. Data Generators
**Purpose:** Produce synthetic events to 4 Kafka topics at specified rates

**Architecture:** Multi-threaded with `DataCorrelationManager` for referential integrity

**Four generator threads:**

1. **ShipmentEventGenerator** (50-80 events/sec)
   - Lifecycle: CREATED → PICKED → PACKED → DISPATCHED → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED
   - 5% EXCEPTION rate, 2% CANCELLED
   - Realistic timing: picking (10-30 min), packing (5-15 min), transit (2-8 hours)

2. **VehicleTelemetryGenerator** (20-30 events/sec)
   - Route simulation between warehouses and delivery zones
   - Speed: 0 km/h (loading), 40-55 km/h (city), 80-120 km/h (motorway)
   - Realistic fuel consumption
   - Status: 60% EN_ROUTE, 25% IDLE, 10% LOADING/UNLOADING, 5% MAINTENANCE

3. **WarehouseOperationGenerator** (15-25 events/sec)
   - Respects operating hours (8 AM - 6 PM local time)
   - Links PICK/PACK to active shipments
   - 3% error rate
   - Operations: RECEIVING, PUTAWAY, PICK, PACK, LOAD, INVENTORY_ADJUSTMENT, CYCLE_COUNT

4. **OrderStatusGenerator** (10-15 events/sec)
   - Creates orders with 1-3 shipments
   - SLA: STANDARD (5d), EXPRESS (2d), SAME_DAY (12h), CRITICAL (4h)
   - 90% on-time target

**Event rate control:**
- Peak (8 AM - 6 PM): 100% rate
- Off-peak (6 PM - 10 PM): 40% rate
- Night (10 PM - 6 AM): 10% rate
- Weekends: 30% of weekday rate

**Critical file:** `data-generators/src/main/java/com/smartship/generators/DataCorrelationManager.java`

### 4. Kafka Streams Processor
**Purpose:** Build 6 real-time materialized views (state stores)

**Deployment:** StatefulSet with headless service for multi-instance support

**Multi-Instance Architecture:**
```
Query-API → Instance Discovery → [Pod-0, Pod-1, Pod-2]
         ↓
    - Specific Key Query → metadataForKey() → Route to Single Pod
    - Aggregate Query → allMetadataForStore() → Query All Pods (parallel) → Merge
```

**Key Configuration:**
- `APPLICATION_SERVER`: Set via environment variable to `$(POD_NAME).streams-processor-headless.smartship.svc.cluster.local:7070`
- Headless service enables DNS-based pod discovery
- StreamsMetadata endpoints expose instance information

**Topology:**
```
Kafka Topics → Stream Processing → State Stores
    │
    ├─ shipment.events
    │   ├─→ Group by status → active-shipments-by-status
    │   ├─→ Group by customer → shipments-by-customer
    │   ├─→ Filter late → late-shipments
    │   └─→ Window 1h → hourly-delivery-performance
    │
    ├─ vehicle.telemetry
    │   └─→ Latest by vehicle_id → vehicle-current-state
    │
    ├─ warehouse.operations
    │   └─→ Window 15min → warehouse-realtime-metrics
    │
    └─ order.status (joined with shipments)
```

**Six state stores:**

1. **active-shipments-by-status** (KeyValue)
   - Key: ShipmentEventType enum
   - Value: List<ShipmentSummary>

2. **vehicle-current-state** (KeyValue)
   - Key: vehicle_id
   - Value: VehicleState (telemetry + load + assigned shipments)

3. **shipments-by-customer** (KeyValue)
   - Key: customer_id
   - Value: CustomerShipmentStats (counts, in-transit, delivered, late)

4. **warehouse-realtime-metrics** (Windowed, 15-min tumbling)
   - Key: warehouse_id
   - Value: WarehouseMetrics (operation counts, avg times, error rate)

5. **late-shipments** (KeyValue)
   - Key: shipment_id
   - Value: LateShipmentDetails (customer, SLA, status, delay)

6. **hourly-delivery-performance** (Windowed, 1-hour hopping)
   - Key: warehouse_id + hour
   - Value: DeliveryStats (on-time, late, p95, avg)

**Features:**
- Interactive Queries enabled (HTTP server on port 7070)
- Exactly-once semantics
- Automatic state recovery from changelog topics
- Multi-instance support via StreamsMetadata API

**Interactive Query Endpoints:**
- `GET /state/{storeName}` - Query all entries in a state store
- `GET /state/{storeName}/{key}` - Query specific key in state store
- `GET /metadata/instances/{storeName}` - Get all instances hosting a state store
- `GET /metadata/instance-for-key/{storeName}/{key}` - Get instance hosting a specific key
- `GET /health` - Health check endpoint

**Critical files:**
- `streams-processor/src/main/java/com/smartship/streams/topology/LogisticsTopology.java`
- `streams-processor/src/main/java/com/smartship/streams/InteractiveQueryServer.java`
- `streams-processor/src/main/java/com/smartship/streams/StreamsMetadataResponse.java`

### 5. Query API (Quarkus)
**Purpose:** REST API for LLM to query real-time and reference data

**Technology:** Quarkus 3.30.x (Supersonic Subatomic Java)

**Why Quarkus:**
- ✓ **Fast startup:** <100ms (vs. ~10s for Spring Boot)
- ✓ **Low memory:** ~30MB RSS with native image (vs. ~200MB JVM)
- ✓ **Kubernetes-native:** Built for containers and cloud
- ✓ **Developer joy:** Live reload, dev services, unified config
- ✓ **Native compilation:** GraalVM for minimal container images

**Service Layers:**

1. **StreamsInstanceDiscoveryService** (Multi-Instance Support)
   - Discovers streams-processor instances via headless service DNS
   - Health checks instances and randomly selects from healthy ones
   - Caches instance metadata for 30 seconds (Quarkus Cache)
   - Uses `InetAddress.getAllByName()` for DNS resolution

2. **KafkaStreamsQueryService**
   - HTTP client to Kafka Streams Interactive Queries (port 7070)
   - Queries all 6 state stores
   - Routes specific key queries to correct instance via `metadataForKey()`
   - Aggregates results from all instances using parallel `CompletableFuture` queries

3. **PostgresQueryService**
   - Quarkus Reactive PostgreSQL client (Mutiny)
   - Queries 6 reference tables: customers, warehouses, vehicles, products, drivers, routes

4. **QueryOrchestrationService**
   - Routes queries to appropriate service(s)
   - Joins data from multiple sources
   - Formats responses for LLM

**API endpoints:**
- `POST /api/query/realtime` - Real-time Kafka Streams queries
- `POST /api/query/reference` - PostgreSQL reference data
- `POST /api/query/hybrid` - Multi-source queries with joins

**Example query workflow:**
```
Query: "Which shipments for ACME Corp are currently delayed?"

1. PostgreSQL: Find customer_id for "ACME Corp"
2. Kafka Streams: Query late-shipments state store, filter by customer_id
3. Enrich with shipment details from shipments-by-customer
4. Return JSON with sources metadata
```

**Quarkus Extensions:**
```xml
<dependencies>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest-jackson</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-reactive-pg-client</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-apicurio-registry-avro</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-openapi</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-health</artifactId>
    <!-- CRITICAL: Required for Kubernetes liveness/readiness probes -->
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-container-image-jib</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-cache</artifactId>
    <!-- Required for instance discovery caching -->
  </dependency>
</dependencies>
```

**Critical Quarkus Configuration (application.properties):**

The Query API requires specific configuration in `query-api/src/main/resources/application.properties` to ensure compatibility with Java 25 and proper Kubernetes integration:

```properties
# Java 25 base image for container builds
# CRITICAL: Without this, container will use default Java 21 image causing UnsupportedClassVersionError
quarkus.jib.base-jvm-image=eclipse-temurin:25-jdk-ubi10-minimal

# Streams processor connection (multi-instance support)
streams-processor.headless-service=streams-processor-headless.smartship.svc.cluster.local
streams-processor.port=7070

# Instance discovery cache (30 second TTL)
quarkus.cache.caffeine.streams-instances.expire-after-write=30S
quarkus.cache.caffeine.streams-instances.maximum-size=10

# Container image configuration
quarkus.container-image.group=smartship
quarkus.container-image.name=query-api
quarkus.container-image.tag=latest
quarkus.container-image.builder=jib
```

**Why these configurations matter:**
- **Base image**: Compiled Java 25 code requires Java 25 runtime. Default Quarkus base images use Java 21.
- **Health extension**: Without `quarkus-smallrye-health`, Kubernetes probes fail with HTTP 404, causing pod restarts.
- **Headless service**: Enables DNS-based discovery of all streams-processor instances.
- **Cache**: Reduces overhead by caching instance metadata for 30 seconds.

### 6. PostgreSQL Database
**Purpose:** Store reference data (200 customers, 5 warehouses, 50 vehicles, 10K products, 75 drivers, 100 routes)

**Schema:** 6 tables
- `customers` - Company data, SLA tiers, account status
- `warehouses` - Rotterdam, Frankfurt, Barcelona, Warsaw, Stockholm
- `vehicles` - 60% vans, 30% box trucks, 10% semi-trailers
- `products` - 10K SKUs across 5 categories
- `drivers` - 75 drivers with certifications
- `routes` - 100 predefined routes with distance/time estimates

**Deployment:** StatefulSet with 10Gi PV (minikube) or 100Gi (cloud)

**Critical file:** `kubernetes/infrastructure/init.sql`

## Technology Stack

### Core Technologies (✅ Phase 1 Implemented)
- **Java:** 25 LTS (eclipse-temurin:25-jdk-ubi10-minimal base image, records, pattern matching, GraalVM native image support)
- **Build Tool:** Maven 3.9+ (superior Avro plugin, simpler multi-module)
- **Kafka:** 4.1.1 (clients + streams) with **KRaft mode** (no ZooKeeper)
- **Avro:** 1.12.1
- **Apicurio Registry:** 3.1.4 (Avro Serdes)
- **Quarkus:** 3.30.1 (REST API, Kafka integration, reactive PostgreSQL)
- **SLF4J:** 2.0.17
- **Logback:** 1.5.12
- **PostgreSQL:** 15 (postgres:15-alpine)
- **Strimzi Operator:** 0.49.0 (supports Kafka 4.1.1 with KRaft)
- **Containerization:** Jib Maven Plugin 3.5.1 with podman/docker support
- **Deployment Automation:** Python 3.9+ scripts

### Maven Plugins (✅ Phase 1 Configured)
- **avro-maven-plugin 1.12.1** - Generate Java from .avsc files
- **jib-maven-plugin 3.5.1** - Build optimized container images (data-generators, streams-processor)
  - Supports both podman and docker via `-Djib.dockerClient.executable`
- **quarkus-maven-plugin 3.30.1** - Build Quarkus applications (JVM and native images)
- **maven-compiler-plugin 3.13.0** - Java 25 compilation
- **maven-surefire-plugin 3.5.2** - Unit tests
- **maven-failsafe-plugin 3.5.2** - Integration tests

## Kubernetes Deployment Strategy (✅ Phase 1 Implemented)

### Organization: Kustomize
**Decision:** Kustomize (not Helm) for simpler YAML-based configuration

```
kubernetes/
├── base/                           # Common resources (✅ Phase 1)
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   ├── kafka-cluster.yaml          # Strimzi Kafka CR with KRaft (KafkaNodePool + Kafka)
│   ├── kafka-topic.yaml            # KafkaTopic CR (shipment.events)
│   ├── apicurio-registry.yaml      # Deployment + Service
│   ├── postgresql.yaml             # StatefulSet + Service + ConfigMap
│   ├── data-generators.yaml        # Deployment
│   ├── streams-processor.yaml      # StatefulSet + Headless Service + ClusterIP Service
│   └── query-api.yaml              # Deployment + Service
├── applications/                   # Application manifests (deployed separately)
│   ├── data-generators.yaml        # Deployment
│   ├── streams-processor.yaml      # StatefulSet + Headless Service
│   └── query-api.yaml              # Deployment + Service
└── overlays/
    ├── minikube/                   # Laptop-friendly (✅ Phase 1)
    │   ├── kustomization.yaml
    │   └── resource-limits-patch.yaml
    └── cloud/                      # Cloud-optimized - Phase 5
        ├── kustomization.yaml
        ├── resource-scaling-patch.yaml
        └── storage-class-patch.yaml
```

**Deploy:**
```bash
kubectl apply -k kubernetes/overlays/minikube
kubectl apply -k kubernetes/overlays/cloud
```

### Deployment Order (✅ Phase 1 Implemented)

**Phase 1: Operators** (prerequisites)
1. ✅ Strimzi Kafka Operator 0.49.0 - Installed via Python script
2. ⏭️ Apicurio Registry Operator - Not used in Phase 1 (standalone deployment instead)

**Phase 2: Infrastructure**
3. ✅ PostgreSQL StatefulSet with ConfigMap for init.sql
4. ✅ Kafka cluster (via Strimzi Kafka CR) - **KRaft mode with KafkaNodePool**
5. ✅ Apicurio Registry - Standalone Deployment (in-memory storage)
6. ✅ Kafka Topics (1 KafkaTopic CR: shipment.events)

**Phase 3: Seed Data**
7. ✅ PostgreSQL seed data loaded via init.sql ConfigMap (5 warehouses)

**Phase 4: Applications**
8. ✅ Data Generators Deployment (1 generator: ShipmentEventGenerator)
9. ✅ Streams Processor StatefulSet + Headless Service + ClusterIP Service (port 7070)
10. ✅ Query API Deployment + Service (port 8080)

### Resource Allocation (✅ Phase 1 Implemented)

#### Minikube (Laptop)
**Minikube setup:** `minikube start --cpus=4 --memory=12288 --disk-size=50g`

**Total resources:** ~1.5 CPU, ~3.5Gi memory (KRaft saves ~400Mi vs ZooKeeper)

| Component | CPU Request | CPU Limit | Memory Request | Memory Limit | Replicas | Status |
|-----------|------------|-----------|----------------|--------------|----------|--------|
| Kafka Broker (KRaft) | 500m | 1000m | 1Gi | 2Gi | 1 | ✅ |
| ~~ZooKeeper~~ | ~~200m~~ | ~~500m~~ | ~~512Mi~~ | ~~1Gi~~ | ~~1~~ | ❌ Not used (KRaft) |
| Apicurio Registry | 150m | 300m | 256Mi | 512Mi | 1 | ✅ |
| PostgreSQL | 200m | 400m | 256Mi | 512Mi | 1 | ✅ |
| Data Generators | 150m | 300m | 256Mi | 512Mi | 1 | ✅ |
| Streams Processor (StatefulSet) | 400m | 800m | 768Mi | 1536Mi | 1-3 | ✅ |
| Query API (JVM) | 200m | 400m | 256Mi | 512Mi | 1 | ✅ Phase 1 |
| Query API (native) | 100m | 250m | 64Mi | 128Mi | 1 | ⏭️ Phase 5 |

**Storage:**
- Kafka: 10Gi PV (includes KRaft metadata)
- PostgreSQL: 5Gi PV

#### Cloud (Scalable)
**Higher resources with auto-scaling:**

| Component | CPU Request | CPU Limit | Memory Request | Memory Limit | Replicas |
|-----------|------------|-----------|----------------|--------------|----------|
| Kafka Broker | 1000m | 2000m | 2Gi | 4Gi | 3 |
| ZooKeeper | 500m | 1000m | 1Gi | 2Gi | 3 |
| Apicurio Registry | 500m | 1000m | 1Gi | 2Gi | 2 |
| PostgreSQL | 1000m | 2000m | 2Gi | 4Gi | 1 |
| Data Generators | 500m | 1000m | 1Gi | 2Gi | 2 |
| Streams Processor | 1000m | 2000m | 2Gi | 4Gi | 2 |
| Query API | 500m | 1000m | 1Gi | 2Gi | 3 (HPA) |

## Development Workflow (✅ Phase 1 Implemented)

### Local Setup (Quick Start)
```bash
# Prerequisites: Java 25, Maven 3.9+, Podman/Docker, minikube, kubectl, Python 3.9+

# 1. Start minikube
minikube start --cpus=4 --memory=12288 --disk-size=50g

# 2. Setup infrastructure (Strimzi, Kafka, Apicurio, PostgreSQL)
python3 scripts/01-setup-infra.py

# 3. Build all modules and container images
# Optional: export CONTAINER_RUNTIME=podman  # or docker (default: podman)
python3 scripts/02-build-all.py

# 4. Deploy applications
python3 scripts/03-deploy-apps.py

# 5. Validate deployment
python3 scripts/04-validate.py

# 6. Watch deployment
kubectl get pods -n smartship -w

# 7. Cleanup when done
python3 scripts/05-cleanup.py
```

**Python Scripts:**
- ✅ `scripts/common.py` - Shared utilities with podman/docker support
  - `kubectl()` - kubectl wrapper
  - `wait_for_condition()` - wait for Deployment conditions
  - `wait_for_statefulset_ready()` - wait for StatefulSet pods
  - `verify_kafka_data_flow()` - verify Kafka topic data flow
  - `setup_container_runtime()` - podman/docker detection
- ✅ `scripts/01-setup-infra.py` - Infrastructure deployment
- ✅ `scripts/02-build-all.py` - Build all modules and images
- ✅ `scripts/03-deploy-apps.py` - Deploy applications (handles StatefulSet migration)
- ✅ `scripts/04-validate.py` - End-to-end validation (includes metadata endpoints)
- ✅ `scripts/05-cleanup.py` - Cleanup deployment

### Build Commands (✅ Phase 1 Implemented)
```bash
# Build everything (automated)
python3 scripts/02-build-all.py

# OR manually:

# Build schemas and common first
mvn clean install -pl schemas,common

# Build individual modules
mvn clean package -pl data-generators
mvn clean package -pl streams-processor

# Build Quarkus Query API (JVM mode - Phase 1)
cd query-api
mvn clean package
# Output: JVM-based container image (fast iteration during development)

# Build container images with Jib (supports podman/docker)
mvn compile jib:dockerBuild -pl data-generators,streams-processor
# With podman: add -Djib.dockerClient.executable=podman

# Build Quarkus Query API container (JVM mode)
cd query-api
mvn package -Dquarkus.container-image.build=true
# With podman: add -Dquarkus.container-image.builder=podman

# Load images into minikube (if using podman)
minikube image load smartship/data-generators:latest
minikube image load smartship/streams-processor:latest
minikube image load smartship/query-api:latest

# Build Quarkus Query API (native image - Phase 5)
cd query-api
./mvnw package -Pnative -Dquarkus.native.container-build=true
# Output: Native executable (~50MB, <100ms startup)
```

### Testing and Validation
```bash
# Check Kafka topics
kubectl exec -it events-cluster-dual-role-0 -n smartship -- \
  bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# Monitor event production
kubectl exec -it events-cluster-dual-role-0 -n smartship -- \
  bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic shipment.events --from-beginning --max-messages 5

# Query Apicurio Registry
kubectl port-forward svc/apicurio-registry 8080:8080 -n smartship
curl http://localhost:8080/apis/registry/v2/groups/com.smartship.logistics.events/artifacts | jq

# Query Kafka Streams state stores
kubectl port-forward deployment/streams-processor 7070:7070 -n smartship
curl http://localhost:7070/state/active-shipments-by-status/IN_TRANSIT | jq
curl http://localhost:7070/state/late-shipments | jq

# Test Query API
kubectl port-forward svc/query-api 8081:8080 -n smartship

curl -X POST http://localhost:8081/api/query/realtime \
  -H "Content-Type: application/json" \
  -d '{"query": "Show all delayed shipments"}' | jq

curl -X POST http://localhost:8081/api/query/hybrid \
  -H "Content-Type: application/json" \
  -d '{"query": "Show vehicles near Rotterdam with capacity"}' | jq

# View logs
kubectl logs -f deployment/data-generators -n smartship
kubectl logs -f deployment/streams-processor -n smartship
kubectl logs -f deployment/query-api -n smartship

# PostgreSQL access
kubectl port-forward svc/postgresql 5432:5432 -n smartship
psql -h localhost -U smartship -d smartship -c "SELECT COUNT(*) FROM customers;"
```

## Implementation Strategy: End-to-End Minimal (Vertical Slice)

**Approach:** Build a minimal working version of ALL components first, then iterate to add features.

**Why this approach:**
- ✓ Validates entire architecture early
- ✓ Identifies integration issues quickly
- ✓ Provides working demo sooner
- ✓ Allows for iterative refinement

## Implementation Phases

### Phase 1: Minimal End-to-End ✅ COMPLETED
**Status:** ✅ Complete
**Timeline:** Completed December 2025
**Goal:** Working system with 1 topic, 1 state store, basic query capability

**Scope (Implemented):**
- ✅ **Infrastructure:** Kafka 4.1.1 (1 broker with KRaft, no ZooKeeper), Apicurio Registry 3.1.4, PostgreSQL 15
- ✅ **Schema:** 1 Avro schema (shipment-event.avsc with 4 fields)
- ✅ **Generator:** 1 simple generator (ShipmentEventGenerator: CREATED → IN_TRANSIT → DELIVERED)
- ✅ **Streams:** 1 state store (active-shipments-by-status) with Interactive Queries
- ✅ **Query API:** Quarkus 3.30.1 REST API with 2 endpoints (JVM mode)
  - `GET /api/shipments/by-status/{status}`
  - `GET /api/shipments/status/all`
- ✅ **Database:** 1 table (warehouses with 5 European locations)
- ✅ **Deployment:** Python automation scripts with podman/docker support
- ✅ **Kubernetes:** Kustomize base + minikube overlay

**Tasks Completed:**
1. ✅ Created Maven parent POM with 6 modules (schemas, common, database, data-generators, streams-processor, query-api)
2. ✅ Defined 1 Avro schema: shipment-event.avsc (4 fields: shipment_id, warehouse_id, event_type, timestamp)
3. ✅ Created common module with KafkaConfig + ApicurioConfig
4. ✅ Created database module with init.sql (warehouses table + 5 seed rows)
5. ✅ Created Kubernetes base manifests (Kafka KRaft, Apicurio, PostgreSQL, applications)
6. ✅ Created Kustomize minikube overlay with resource limits
7. ✅ Created Python deployment scripts (5 scripts + common.py)
8. ✅ Implemented ShipmentEventGenerator (generates CREATED → IN_TRANSIT → DELIVERED every 6 seconds)
9. ✅ Implemented Kafka Streams topology with LogisticsTopology + InteractiveQueryServer
10. ✅ Implemented Quarkus Query API (QueryResource + KafkaStreamsQueryService)
11. ✅ Added quarkus-smallrye-health dependency for Kubernetes health checks
12. ✅ Configured Java 25 base image for Quarkus container builds
13. ✅ Fixed Java version compatibility issues (UnsupportedClassVersionError)
14. ✅ Deployed all components to minikube
15. ✅ Validated end-to-end: generator → Kafka → Streams → Query API
16. ✅ Implemented multi-instance streams-processor support:
    - Converted Deployment to StatefulSet with headless service
    - Added StreamsMetadata endpoints (`/metadata/instances`, `/metadata/instance-for-key`)
    - Implemented StreamsInstanceDiscoveryService with DNS-based pod discovery
    - Added parallel query aggregation with CompletableFuture
    - Added quarkus-cache for instance metadata caching
    - Updated deployment scripts for StatefulSet handling

**Deliverables (Achieved):**
- ✅ Full stack running on minikube (~1.5 CPU, ~3.5Gi memory)
- ✅ 1 generator producing shipment lifecycle events
- ✅ 1 Kafka Streams state store populated with counts
- ✅ Query API endpoints returning real-time data
- ✅ Interactive Queries available on port 7070
- ✅ REST API with OpenAPI/Swagger UI on port 8080
- ✅ Complete README.md with deployment instructions
- ✅ Can query: "Show all IN_TRANSIT shipments" and get live counts
- ✅ Multi-instance streams-processor support with horizontal scaling capability
- ✅ StreamsMetadata endpoints for instance discovery

**Key Implementation Decisions:**
- Used **Kafka 4.1.1 with KRaft** instead of ZooKeeper (saves ~400Mi memory)
- Used **Strimzi 0.49.0** for KRaft support
- Used **Java 25 LTS** as the target JVM with eclipse-temurin:25-jdk-ubi10-minimal base images
- Used **Python scripts** for deployment automation (better than bash for cross-platform)
- Used **podman as default** container runtime with docker fallback
- Used **Quarkus JVM mode** for Phase 1 (native image deferred to Phase 5)
- Added **quarkus-smallrye-health** extension for Kubernetes health probe support
- Configured **Quarkus Jib base image** explicitly to match Java 25 compilation target
- Used **in-memory Apicurio Registry** for simplicity (persistent storage in later phases)
- Updated to **Apicurio Registry 3.1.4** for improved compatibility
- Used **StatefulSet with headless service** for streams-processor (enables stable network identities)
- Implemented **DNS-based instance discovery** via `InetAddress.getAllByName()` with random selection
- Used **Quarkus Cache** for instance metadata caching (30-second TTL)
- Implemented **parallel query aggregation** with `CompletableFuture` for multi-instance queries

### Phase 2: Add Remaining Topics & Generators ⏭️ PENDING
**Status:** Pending
**Goal:** All 4 topics producing events, with data correlation

**Scope:**
- Add 3 more schemas: vehicle-telemetry, warehouse-operation, order-status
- Add 3 more generators: VehicleTelemetry, WarehouseOperation, OrderStatus
- Implement DataCorrelationManager for referential integrity
- Add 3 more PostgreSQL tables: vehicles, products, customers (with seed data)

**Building on Phase 1:**
- Extend existing schemas module with 3 new .avsc files
- Extend data-generators module with additional generator classes
- Update kubernetes/infrastructure/init.sql with new tables
- Update Kubernetes manifests for increased resource allocation

### Phase 3: Complete Kafka Streams State Stores ⏭️ PENDING
**Status:** Pending
**Goal:** All 6 materialized views operational

**Scope:**
- Add 5 more state stores (vehicle-current-state, shipments-by-customer, warehouse-realtime-metrics, late-shipments, hourly-delivery-performance)
- Implement windowed aggregations
- Enhance Interactive Queries API with all state stores

**Building on Phase 1:**
- Extend LogisticsTopology.java with 5 additional state stores
- Update InteractiveQueryServer.java with new query endpoints
- Update Query API to expose all state stores

### Phase 4: Complete Query API ⏭️ PENDING
**Status:** Pending
**Goal:** Full LLM query capability with multi-source queries

**Scope:**
- Add /api/query/reference endpoint for PostgreSQL queries
- Add /api/query/hybrid endpoint for multi-source queries
- Implement PostgresQueryService with Quarkus reactive PostgreSQL client
- Implement QueryOrchestrationService for joining data
- Enhance OpenAPI documentation

**Building on Phase 1:**
- Extend query-api module with PostgreSQL integration
- Add quarkus-reactive-pg-client dependency
- Implement hybrid queries combining Kafka Streams + PostgreSQL data

### Phase 5: Refinement & Production-Ready ⏭️ PENDING
**Status:** Pending
**Goal:** Production-quality implementation with docs and testing

**Scope:**
- Build Quarkus Query API as **native image** (target: <100ms startup, <128Mi memory)
- Add advanced features (exception injection, time-based event rates, realistic geography)
- Create Kustomize cloud overlay with HPA
- Implement persistent storage for Apicurio Registry
- Comprehensive testing (unit, integration, performance)
- Enhanced documentation and troubleshooting guides

**Building on Phase 1:**
- Create query-api native image build
- Update query-api.yaml with native image resources
- Add kubernetes/overlays/cloud/ for production deployment
- Implement comprehensive test suite

### Phase 6: Demo Optimization & Polish ⏭️ PENDING
**Status:** Pending
**Goal:** Demo-ready with example queries and presentations

**Deliverables:**
- Demo-ready system with all features
- Sample LLM query scripts for common logistics questions
- Presentation materials and architecture diagrams
- Performance benchmarks and metrics dashboards
- Video walkthrough and demo script

**Building on Phase 1:**
- Create demo scenarios showcasing real-time queries
- Implement example LLM integration
- Create Grafana dashboards for metrics visualization

## Quarkus Benefits Summary

**Why Quarkus over Spring Boot for this demo:**

| Aspect | Quarkus (Native) | Spring Boot (JVM) |
|--------|------------------|-------------------|
| Startup Time | <100ms | ~10 seconds |
| Memory Usage | 30-50MB RSS | 200-300MB RSS |
| Container Image Size | ~50MB | ~200MB+ |
| Cold Start (K8s) | Instant | Slow |
| Developer Experience | Live reload, dev services | Good but slower |
| Kubernetes Integration | Native (designed for) | Added on |
| Native Compilation | Built-in GraalVM | Requires complex setup |

**Impact on this demo:**
- ✓ Query API pods scale from 0→1 in <1 second (vs. ~15s with Spring Boot)
- ✓ Fits laptop resources better (saves ~400MB memory)
- ✓ Demonstrates cloud-native best practices
- ✓ Faster iteration during development (live reload)

## Success Criteria

### Functional Requirements
- ✓ All 4 Kafka topics receiving events at specified rates (±10%)
- ✓ All 6 state stores populated with correct data
- ✓ Query API answers all sample queries from design doc
- ✓ PostgreSQL contains complete reference data
- ✓ Apicurio managing schemas with backward compatibility
- ✓ System runs stable for 24+ hours

### Non-Functional Requirements
- ✓ Minikube uses ≤8GB RAM, ≤3 CPU cores
- ✓ Event-to-state-store latency <5 seconds (p95)
- ✓ Query API response time <500ms (p95)
- ✓ Zero data loss (exactly-once semantics)
- ✓ App restart recovers state within 2 minutes
- ✓ New developer can deploy in <2 hours
- ✓ Query API native image starts in <100ms

### Quality Requirements
- ✓ Unit tests passing (>70% coverage)
- ✓ Integration tests passing
- ✓ No critical security vulnerabilities
- ✓ Code follows Java style guide
- ✓ All public APIs documented

## Critical Files to Create

1. **pom.xml** - Parent POM with dependency management
2. **schemas/src/main/avro/shipment-event.avsc** - Core Avro schema (+ 3 others)
3. **data-generators/src/main/java/com/smartship/generators/DataCorrelationManager.java** - Central coordinator
4. **streams-processor/src/main/java/com/smartship/streams/topology/LogisticsTopology.java** - Kafka Streams topology
5. **streams-processor/src/main/java/com/smartship/streams/InteractiveQueryServer.java** - Interactive Queries HTTP server
6. **streams-processor/src/main/java/com/smartship/streams/StreamsMetadataResponse.java** - StreamsMetadata DTO
7. **query-api/src/main/java/com/smartship/api/QueryResource.java** - Quarkus JAX-RS endpoint
8. **query-api/src/main/java/com/smartship/api/services/StreamsInstanceDiscoveryService.java** - Instance discovery
9. **query-api/src/main/java/com/smartship/api/model/StreamsInstanceMetadata.java** - Instance metadata DTO
10. **kubernetes/applications/streams-processor.yaml** - StatefulSet + Headless Service
11. **kubernetes/overlays/minikube/kustomization.yaml** - Minikube deployment
12. **kubernetes/infrastructure/init.sql** - PostgreSQL DDL

## Next Steps

✅ **Phase 1 COMPLETED** - Minimal end-to-end system is fully operational and ready for deployment.

**To deploy Phase 1:**
```bash
cd /home/tcooper/repos/redhat/realtime-context-demo

# 1. Start minikube
minikube start --cpus=4 --memory=12288 --disk-size=50g

# 2. Deploy infrastructure
python3 scripts/01-setup-infra.py

# 3. Build all modules
python3 scripts/02-build-all.py

# 4. Deploy applications
python3 scripts/03-deploy-apps.py

# 5. Validate deployment
python3 scripts/04-validate.py
```

**Future Implementation:**
- Phase 2-6 will build upon the solid foundation established in Phase 1
- Each phase adds incremental functionality while maintaining the working system
- All infrastructure and tooling from Phase 1 will be reused and extended

## Phase 1 Success Metrics (All Achieved)

✅ **Functional Requirements:**
- All infrastructure pods healthy and running
- Data generator producing events every 6 seconds
- State store populated with shipment counts
- Query API returning real-time data
- Interactive Queries API functional
- End-to-end latency <5 seconds
- Multi-instance streams-processor support operational
- StreamsMetadata endpoints functional

✅ **Technical Requirements:**
- Minikube using ~1.5 CPU, ~3.5Gi memory
- Kafka 4.1.1 with KRaft (no ZooKeeper)
- Quarkus 3.30.1 REST API operational
- Python deployment automation working
- Podman/Docker support functional
- StatefulSet with headless service for streams-processor
- DNS-based instance discovery with health checks
- Parallel query aggregation with CompletableFuture

✅ **Documentation:**
- Complete README.md with deployment instructions
- Implementation plan updated with actual implementation
- All critical files created and documented
