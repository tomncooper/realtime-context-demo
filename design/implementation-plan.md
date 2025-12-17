# SmartShip Logistics Implementation Plan

**Status:** Phase 1 ✅ COMPLETED | Phase 2 ✅ COMPLETED | Phase 3 ✅ COMPLETED | Phase 4 ✅ COMPLETED | Phase 5 ✅ COMPLETED | Phase 6 Pending | Phase 7-8 Pending (LLM Chatbot)

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
├── schemas/                         # Avro schemas → generates Java classes (✅ Phase 2: 4 schemas)
├── common/                          # Shared utilities (✅ Phase 1: KafkaConfig, ApicurioConfig)
├── data-generators/                 # Synthetic event producers (✅ Phase 2: 4 generators + DataCorrelationManager)
├── streams-processor/               # Kafka Streams (✅ Phase 3: 6 state stores, 3 topics consumed)
├── query-api/                       # Quarkus REST API (✅ Phase 3: 6 state store endpoints, Phase 5: native image)
├── kubernetes/
│   ├── infrastructure/              # Infrastructure resources (✅ Phase 1)
│   │   └── init.sql                 # PostgreSQL DDL and seed data (✅ Phase 2: 6 tables, 10,430 records)
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

**Reference Data Loading (Single Source of Truth):**
At startup, the data-generators module loads all reference data from PostgreSQL via `ReferenceDataLoader`:
- Connects to PostgreSQL with retry logic (30 attempts, exponential backoff)
- Loads 6 tables: warehouses, customers, vehicles, drivers, products, routes
- Initializes `DataCorrelationManager` with loaded data
- Eliminates hardcoded reference data duplication

**Key Files:**
- `ReferenceDataLoader.java` - PostgreSQL JDBC loader with retry
- `model/*.java` - Reference data model classes (Warehouse, Customer, Vehicle, Driver, Product, Route, ReferenceData)
- `DataCorrelationManager.java` - Singleton initialized from loaded data

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
6. ✅ Kafka Topics (4 KafkaTopic CRs: shipment.events, vehicle.telemetry, warehouse.operations, order.status)

**Phase 3: Seed Data**
7. ✅ PostgreSQL seed data loaded via init.sql ConfigMap (6 tables: 5 warehouses, 200 customers, 50 vehicles, 10K products, 75 drivers, 100 routes)

**Phase 4: Applications**
8. ✅ Data Generators Deployment (4 generators: ShipmentEvent, VehicleTelemetry, WarehouseOperation, OrderStatus + GeneratorMain)
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
| Data Generators | 400m | 800m | 768Mi | 1536Mi | 1 | ✅ Phase 2 |
| Streams Processor (StatefulSet) | 400m | 800m | 768Mi | 1536Mi | 1-3 | ✅ |
| Query API (JVM) | 200m | 400m | 256Mi | 512Mi | 1 | ✅ Phase 1 |
| Query API (native) | 100m | 250m | 64Mi | 128Mi | 1 | ⏭️ Phase 5 |
| Ollama (LLM) | 2000m | 4000m | 4Gi | 8Gi | 1 | ⏭️ Phase 7 |

**Storage:**
- Kafka: 10Gi PV (includes KRaft metadata)
- PostgreSQL: 5Gi PV
- Ollama: 20Gi PV (model storage) - Phase 7

**Phase 7 Minikube Requirements:**
```bash
minikube start --cpus=6 --memory=16384 --disk-size=80g
```

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
| Ollama (LLM) | 4000m | 8000m | 8Gi | 16Gi | 1 |

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

### Phase 2: Add Remaining Topics & Generators ✅ COMPLETED
**Status:** ✅ Complete
**Timeline:** Completed December 2025
**Goal:** All 4 topics producing events, with data correlation

**Scope (Implemented):**
- ✅ **Schemas:** 4 Avro schemas (shipment-event expanded + vehicle-telemetry, warehouse-operation, order-status)
- ✅ **Generators:** 4 generators with DataCorrelationManager for referential integrity
- ✅ **Kafka Topics:** 4 topics (shipment.events, vehicle.telemetry, warehouse.operations, order.status)
- ✅ **PostgreSQL:** 6 tables with 10,430 total records (warehouses, customers, vehicles, products, drivers, routes)

**Tasks Completed:**
1. ✅ Expanded `shipment-event.avsc` with customer_id, expected_delivery, destination fields + 9 enum values
2. ✅ Created `vehicle-telemetry.avsc` with nested GeoLocation and VehicleLoad records
3. ✅ Created `warehouse-operation.avsc` with 7 operation types and errors array
4. ✅ Created `order-status.avsc` with 7 status types and 4 priority levels
5. ✅ Created `DataCorrelationManager.java` singleton for referential integrity across generators
6. ✅ Updated `ShipmentEventGenerator.java` with full 9-state lifecycle (5% exception, 2% cancellation)
7. ✅ Created `VehicleTelemetryGenerator.java` (20-30 events/sec, 50 vehicles)
8. ✅ Created `WarehouseOperationGenerator.java` (15-25 events/sec, 3% error rate)
9. ✅ Created `OrderStatusGenerator.java` (10-15 events/sec, 4 SLA tiers)
10. ✅ Created `GeneratorMain.java` unified entry point
11. ✅ Updated PostgreSQL `init.sql` with 5 new tables and full-scale seed data
12. ✅ Added 3 new KafkaTopic CRs to `kafka-topic.yaml`
13. ✅ Updated `data-generators.yaml` resource limits (768Mi/1536Mi memory, 400m/800m CPU)
14. ✅ Updated `04-validate.py` to verify all 4 topics and 6 PostgreSQL tables

**Deliverables (Achieved):**
- ✅ 4 Kafka topics receiving events at specified rates
- ✅ DataCorrelationManager ensuring valid cross-references between events
- ✅ PostgreSQL with 6 tables: warehouses (5), customers (200), vehicles (50), products (10K), drivers (75), routes (100)
- ✅ Event rates: ~50-80 shipment, 20-30 vehicle, 15-25 warehouse, 10-15 order events/sec
- ✅ Backward compatible with Phase 1 streams-processor (expanded schema is additive)

**Key Implementation Decisions:**
- Used **consistent event rates** (deferred time-based peak/off-peak to later phase)
- Implemented **singleton DataCorrelationManager** initialized from PostgreSQL at startup (single source of truth)
- Created **ReferenceDataLoader** service with JDBC connection and retry logic (30 attempts, exponential backoff)
- Added **model classes** in `data-generators/src/main/java/com/smartship/generators/model/` for type-safe reference data
- Used **generate_series()** in PostgreSQL for efficient bulk seed data generation
- Maintained **backward compatibility** with existing streams-processor (new fields are additions)
- **PostgreSQL is the single source of truth** for all reference data (warehouses, customers, vehicles, drivers, products, routes)

### Phase 3: Complete Kafka Streams State Stores ✅ COMPLETED
**Status:** ✅ Complete
**Timeline:** Completed December 2025
**Goal:** All 6 materialized views operational

**Scope (Implemented):**
- ✅ **State Stores:** 6 state stores consuming 3 Kafka topics (shipment.events, vehicle.telemetry, warehouse.operations)
- ✅ **KeyValue Stores:** active-shipments-by-status, vehicle-current-state, shipments-by-customer, late-shipments
- ✅ **Windowed Stores:** warehouse-realtime-metrics (15-min tumbling), hourly-delivery-performance (1-hour hopping)
- ✅ **Interactive Queries:** HTTP endpoints for all 6 state stores on port 7070
- ✅ **Query API:** REST endpoints for all 6 state stores with OpenAPI documentation

**Tasks Completed:**
1. ✅ Created `LogisticsTopology.java` with 6 state store definitions consuming 3 topics
2. ✅ Created model classes: `VehicleState`, `CustomerShipmentStats`, `LateShipmentDetails`, `DeliveryStats`, `WarehouseMetrics`
3. ✅ Created `JsonSerde` for custom JSON serialization of state store values
4. ✅ Updated `InteractiveQueryServer.java` with 6 state store query endpoints
5. ✅ Updated `QueryResource.java` with 14 REST endpoints across 5 resource groups
6. ✅ Updated `KafkaStreamsQueryService.java` with distributed query support for all stores
7. ✅ Added response model classes for windowed query results
8. ✅ Updated `04-validate.py` to test all 6 state stores and Query API endpoints

**Deliverables (Achieved):**
- ✅ 6 Kafka Streams state stores operational and queryable
- ✅ Real-time aggregations: shipment counts, customer stats, late tracking, delivery performance
- ✅ Windowed aggregations: 15-minute warehouse metrics, 1-hour hopping delivery stats
- ✅ Interactive Queries API exposing all state stores on port 7070
- ✅ Query API with 14 endpoints covering all state stores
- ✅ Multi-instance query support with parallel aggregation

**Key Implementation Decisions:**
- Used **JsonSerde** for state store value serialization (simpler than Avro for aggregated types)
- Implemented **tumbling window** (15 min) for warehouse metrics and **hopping window** (1 hour, 30 min advance) for delivery performance
- Used **30-minute grace period** for late shipment detection
- Maintained **6-hour retention** for windowed stores
- Note: `order.status` topic consumption deferred to Phase 4 (hybrid queries with PostgreSQL joins)

### Phase 4: Complete Query API ✅ COMPLETED
**Status:** ✅ Complete
**Timeline:** Completed December 2025
**Goal:** Full LLM query capability with multi-source queries

**Scope (Implemented):**
- ✅ **PostgreSQL Integration:** 17 reference data endpoints via `ReferenceDataResource`
- ✅ **Hybrid Queries:** 7 endpoints combining Kafka Streams + PostgreSQL via `HybridQueryResource`
- ✅ **Order State Stores:** 3 new state stores consuming `order.status` topic
- ✅ **PostgresQueryService:** Quarkus reactive PostgreSQL client with Mutiny
- ✅ **QueryOrchestrationService:** Multi-source query orchestration with error handling
- ✅ **HybridQueryResult:** Result wrapper with `warnings` field for data quality indicators
- ✅ **Enhanced OpenAPI:** Full documentation for all endpoints

**Tasks Completed:**
1. ✅ Created `PostgresQueryService.java` with Quarkus reactive PostgreSQL client
2. ✅ Created `ReferenceDataResource.java` with 17 reference data endpoints
3. ✅ Created `HybridQueryResource.java` with 7 hybrid query endpoints
4. ✅ Created `QueryOrchestrationService.java` for multi-source query orchestration
5. ✅ Extended `LogisticsTopology.java` with 3 order state stores (order-current-state, orders-by-customer, order-sla-tracking)
6. ✅ Created model classes: `EnrichedCustomerOverview`, `EnrichedVehicleState`, `EnrichedLateShipment`, `HybridQueryResult`
7. ✅ Created reference DTOs: `CustomerDto`, `WarehouseDto`, `VehicleDto`, `DriverDto`, `RouteDto`, `ProductDto`
8. ✅ Added `quarkus-reactive-pg-client` dependency
9. ✅ Added graceful error handling with `warnings` field for Kafka Streams connection issues
10. ✅ Updated validation script with correct ID formats

**Deliverables (Achieved):**
- ✅ 9 state stores total (6 original + 3 order stores)
- ✅ 17 PostgreSQL reference data endpoints
- ✅ 7 hybrid query endpoints combining real-time and reference data
- ✅ Graceful error handling with data quality warnings
- ✅ Full OpenAPI/Swagger documentation

**Key Implementation Decisions:**
- Used **Mutiny Uni<T>** for non-blocking reactive PostgreSQL queries
- Added **warnings field** to `HybridQueryResult` to indicate when data sources are unavailable
- Implemented **try-catch blocks** around all Kafka Streams calls with graceful fallback
- Used **builder pattern** for enriched model classes

**ID Formats (Critical for Queries):**
- Customers: `CUST-0001` through `CUST-0200` (4 digits, zero-padded)
- Vehicles: `VEH-001` through `VEH-050` (3 digits)
- Drivers: `DRV-001` through `DRV-075` (3 digits)
- Warehouses: `WH-RTM`, `WH-FRA`, `WH-BCN`, `WH-WAW`, `WH-STO`

### Phase 5: Refinement & Production-Ready ✅ COMPLETED
**Status:** ✅ Complete
**Timeline:** Completed December 2025
**Goal:** Production-quality implementation with native image builds and comprehensive testing

**Scope (Implemented):**
- ✅ **Native Image Build:** Quarkus Query API as GraalVM native image (<100ms startup, <128Mi memory)
- ✅ **Test Coverage:** 5 test classes with JUnit 5, Mockito, Rest-Assured, JaCoCo
- ✅ **Exception Handling:** Consistent JSON error responses via ExceptionMappers
- ✅ **Reflection Configuration:** NativeImageReflectionConfig for 23 model classes
- ✅ **Build Automation:** Python script `--native` flag support

**Tasks Completed:**
1. ✅ Created `NativeImageReflectionConfig.java` registering 23 classes for GraalVM reflection
2. ✅ Created `ExceptionMappers.java` for consistent JSON error responses
3. ✅ Added native profile to `query-api/pom.xml` (Java 21 for native, Java 25 for JVM)
4. ✅ Updated `application.properties` with native image configuration
5. ✅ Updated `02-build-all.py` with `--native` flag support
6. ✅ Updated `query-api.yaml` with native image resource requirements
7. ✅ Created 5 test classes:
   - `PostgresQueryServiceTest.java` - PostgreSQL service unit tests
   - `KafkaStreamsQueryServiceTest.java` - Kafka Streams service unit tests
   - `ReferenceDataResourceTest.java` - Reference data REST endpoint tests
   - `QueryResourceTest.java` - Query API REST endpoint tests
   - `HybridQueryResourceTest.java` - Hybrid query REST endpoint tests

**Deliverables (Achieved):**
- ✅ Native image builds successfully with Mandrel/GraalVM
- ✅ Startup time <100ms in native mode (vs ~10s JVM)
- ✅ Memory usage 64-128Mi in native mode (vs 256-512Mi JVM)
- ✅ Container image size ~50MB native (vs ~200MB JVM)
- ✅ Test coverage for all major services and REST endpoints
- ✅ Consistent error handling across all endpoints

**Key Implementation Decisions:**
- Used **Java 21** for native builds (GraalVM compatibility) while keeping Java 25 for JVM mode
- Used **Mandrel builder image** (`quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21`)
- Used **Quarkus micro image** (`quay.io/quarkus/quarkus-micro-image:2.0`) for minimal container size
- Used **@RegisterForReflection** for all model classes requiring JSON serialization
- Used **@ServerExceptionMapper** for RESTEasy Reactive exception handling

**Native Image Configuration:**
```properties
# Builder and base images
quarkus.native.builder-image=quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21
quarkus.jib.base-native-image=quay.io/quarkus/quarkus-micro-image:2.0
quarkus.native.resources.includes=META-INF/services/**
```

**Native vs JVM Resource Comparison:**
| Mode | Memory Request | Memory Limit | CPU Request | CPU Limit | Startup |
|------|----------------|--------------|-------------|-----------|---------|
| JVM | 256Mi | 512Mi | 200m | 400m | ~10s |
| Native | 64Mi | 128Mi | 100m | 250m | <100ms |

### Phase 6: Demo Optimization & Polish ⏭️ PENDING
**Status:** Pending
**Goal:** Demo-ready with example queries and presentations

**Deliverables:**
- Demo-ready system with all features
- Sample LLM query scripts for common logistics questions
- Presentation materials and architecture diagrams
- Performance benchmarks and metrics dashboards
- Video walkthrough and demo script

**Building on Phase 5:**
- Create demo scenarios showcasing real-time queries
- Create Grafana dashboards for metrics visualization

### Phase 7: LLM Chatbot Integration (Quarkus LangChain4j) ⏭️ PENDING
**Status:** Pending
**Goal:** Intelligent chatbot with real-time data access using Quarkus LangChain4j and Ollama

**Architecture Decision:** Extend the existing `query-api` module rather than creating a new module.

**Rationale:**
- Direct access to `KafkaStreamsQueryService` and other services
- LangChain4j tools integrate seamlessly with existing CDI beans
- Simpler deployment and configuration
- Single entry point for all query operations

**Scope:**
- **AI Service:** LogisticsAssistant with `@RegisterAiService`, session-scoped chat
- **6 Tool Classes:** Shipment, Vehicle, Warehouse, Customer, Performance, ReferenceData tools
- **Input Guardrail:** LogisticsInScopeGuard for question validation
- **Chat Memory:** In-memory session storage with 20-message window
- **Endpoints:** REST (`/api/chat`, `/api/chat/stream`) and WebSocket (`/ws/chat`)
- **LLM Backend:** Ollama with llama3.2 model deployed on Kubernetes

**Dependencies to Add (query-api/pom.xml):**
```xml
<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-core</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-ollama</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-websockets-next</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-reactive-pg-client</artifactId>
</dependency>
```

**Files to Create:**
| File | Description |
|------|-------------|
| `api/ai/LogisticsAssistant.java` | AI service interface with `@RegisterAiService` |
| `api/ai/ChatResource.java` | REST endpoints for chat |
| `api/ai/WebSocketChatEndpoint.java` | WebSocket endpoint for streaming |
| `api/ai/tools/ShipmentQueryTools.java` | Shipment status and late shipment tools |
| `api/ai/tools/VehicleQueryTools.java` | Vehicle state and location tools |
| `api/ai/tools/WarehouseQueryTools.java` | Warehouse metrics tools |
| `api/ai/tools/CustomerQueryTools.java` | Customer shipment stats tools |
| `api/ai/tools/PerformanceQueryTools.java` | Hourly delivery performance tools |
| `api/ai/tools/ReferenceDataTools.java` | PostgreSQL reference data tools |
| `api/ai/guardrails/LogisticsInScopeGuard.java` | Input guardrail |
| `api/ai/memory/InMemoryChatMemoryStore.java` | Chat session memory |
| `kubernetes/infrastructure/ollama.yaml` | Ollama StatefulSet deployment |

**Configuration (application.properties):**
```properties
# Ollama Configuration
quarkus.langchain4j.ollama.base-url=http://ollama.smartship.svc.cluster.local:11434
quarkus.langchain4j.ollama.chat-model.model-id=llama3.2
quarkus.langchain4j.ollama.chat-model.temperature=0.3
quarkus.langchain4j.ollama.timeout=120s

# Dev Services (local development)
quarkus.langchain4j.ollama.devservices.enabled=true

# Chat Memory
quarkus.langchain4j.chat-memory.type=message-window
quarkus.langchain4j.chat-memory.message-window.max-messages=20
```

**Kubernetes Resources (Ollama):**
| Component | CPU Req | CPU Limit | Mem Req | Mem Limit | Storage |
|-----------|---------|-----------|---------|-----------|---------|
| Ollama | 2000m | 4000m | 4Gi | 8Gi | 20Gi |

**Minikube Requirements Update:**
```bash
minikube start --cpus=6 --memory=16384 --disk-size=80g
```

**Building on Phase 4:**
- Leverages existing KafkaStreamsQueryService for real-time data
- Uses existing PostgreSQL connection for reference data
- Extends query-api module with AI capabilities

### Phase 8: Advanced LLM Features & Demo Polish ⏭️ PENDING
**Status:** Pending
**Goal:** Production-quality chatbot with output validation, analytics, and observability

**Scope:**

**Output Guardrails:**
- **DataFactualityGuard:** Validate warehouse IDs are real (WH-RTM, WH-FRA, WH-BCN, WH-WAW, WH-STO)
- **ResponseFormatGuard:** Block stack traces, prevent system prompt leakage

**Analytics Tools:**
- `analyzeWarehouseIssues(warehouseId)` - Cross-source correlation
- `getSystemHealthSummary()` - Fleet-wide operational overview
- `compareWarehouses(wh1, wh2)` - Performance comparison

**Observability:**
- ChatAuditObserver implementing `AuditEventListener`
- Log guardrail executions, tool calls, LLM requests/responses

**Files to Create:**
| File | Description |
|------|-------------|
| `api/ai/guardrails/DataFactualityGuard.java` | Output guardrail for data validation |
| `api/ai/guardrails/ResponseFormatGuard.java` | Output guardrail for format validation |
| `api/ai/tools/AnalyticsTools.java` | Cross-source analytics tools |
| `api/ai/observability/ChatAuditObserver.java` | Audit logging observer |
| `docs/demo-conversations.md` | Example conversation flows |

**Demo Conversation Examples:**
1. **Real-time Tracking:** "What's the current status of shipment SHP-12345?"
2. **Late Shipment Investigation:** "Which shipments are currently delayed and why?"
3. **Warehouse Performance:** "Compare the performance of Rotterdam and Frankfurt warehouses"
4. **Customer SLA Check:** "Is CUST-001 meeting their SLA requirements?"
5. **Fleet Overview:** "Give me a summary of our current fleet utilization"

**Building on Phase 7:**
- Adds output validation to existing AI service
- Extends tool classes with analytics capabilities
- Adds audit logging for compliance and debugging

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

### Phase 1-3 (Core Infrastructure)
1. **pom.xml** - Parent POM with dependency management
2. **schemas/src/main/avro/shipment-event.avsc** - Core Avro schema (+ 3 others)
3. **data-generators/src/main/java/com/smartship/generators/DataCorrelationManager.java** - Central coordinator (initialized from PostgreSQL)
4. **data-generators/src/main/java/com/smartship/generators/ReferenceDataLoader.java** - PostgreSQL JDBC loader with retry
5. **data-generators/src/main/java/com/smartship/generators/model/*.java** - Reference data model classes
6. **streams-processor/src/main/java/com/smartship/streams/topology/LogisticsTopology.java** - Kafka Streams topology
7. **streams-processor/src/main/java/com/smartship/streams/InteractiveQueryServer.java** - Interactive Queries HTTP server
8. **streams-processor/src/main/java/com/smartship/streams/StreamsMetadataResponse.java** - StreamsMetadata DTO
9. **query-api/src/main/java/com/smartship/api/QueryResource.java** - Quarkus JAX-RS endpoint
10. **query-api/src/main/java/com/smartship/api/services/StreamsInstanceDiscoveryService.java** - Instance discovery
11. **query-api/src/main/java/com/smartship/api/model/StreamsInstanceMetadata.java** - Instance metadata DTO
12. **kubernetes/applications/streams-processor.yaml** - StatefulSet + Headless Service
13. **kubernetes/overlays/minikube/kustomization.yaml** - Minikube deployment
14. **kubernetes/infrastructure/init.sql** - PostgreSQL DDL (single source of truth for reference data)

### Phase 7 (LLM Chatbot)
15. **query-api/src/main/java/com/smartship/api/ai/LogisticsAssistant.java** - AI service interface
16. **query-api/src/main/java/com/smartship/api/ai/ChatResource.java** - Chat REST endpoints
17. **query-api/src/main/java/com/smartship/api/ai/WebSocketChatEndpoint.java** - WebSocket streaming
18. **query-api/src/main/java/com/smartship/api/ai/tools/*.java** - 6 tool classes for LLM function calling
19. **query-api/src/main/java/com/smartship/api/ai/guardrails/LogisticsInScopeGuard.java** - Input guardrail
20. **query-api/src/main/java/com/smartship/api/ai/memory/InMemoryChatMemoryStore.java** - Session memory
21. **kubernetes/infrastructure/ollama.yaml** - Ollama StatefulSet + Service

### Phase 8 (Advanced LLM Features)
22. **query-api/src/main/java/com/smartship/api/ai/guardrails/DataFactualityGuard.java** - Output guardrail
23. **query-api/src/main/java/com/smartship/api/ai/guardrails/ResponseFormatGuard.java** - Format guardrail
24. **query-api/src/main/java/com/smartship/api/ai/tools/AnalyticsTools.java** - Cross-source analytics
25. **query-api/src/main/java/com/smartship/api/ai/observability/ChatAuditObserver.java** - Audit logging
26. **docs/demo-conversations.md** - Demo conversation examples

## Next Steps

✅ **Phase 1 COMPLETED** - Minimal end-to-end system with 1 topic, 1 state store.
✅ **Phase 2 COMPLETED** - All 4 topics producing events with full-scale reference data.
✅ **Phase 3 COMPLETED** - All 6 Kafka Streams state stores consuming 3 topics.
✅ **Phase 4 COMPLETED** - Full LLM query capability with 9 state stores, PostgreSQL integration, and hybrid queries.
✅ **Phase 5 COMPLETED** - Native image builds, comprehensive testing, and production hardening.

**To deploy (JVM mode):**
```bash
cd /home/tcooper/repos/redhat/realtime-context-demo

# 1. Start minikube
minikube start --cpus=4 --memory=12288 --disk-size=50g

# 2. Deploy infrastructure
python3 scripts/01-setup-infra.py

# 3. Build all modules (JVM mode)
python3 scripts/02-build-all.py

# 4. Deploy applications
python3 scripts/03-deploy-apps.py

# 5. Validate deployment (tests all 9 state stores, reference data, and hybrid queries)
python3 scripts/04-validate.py
```

**To build native image:**
```bash
# Build query-api as native image (requires more time and memory)
python3 scripts/02-build-all.py --native
```

**Future Implementation:**
- Phase 6 will add demo optimization and Grafana dashboards
- Phase 7 will add LLM chatbot integration with Quarkus LangChain4j and Ollama
- Phase 8 will add advanced LLM features: output guardrails, analytics tools, and observability

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

## Phase 3 Success Metrics (All Achieved)

✅ **Functional Requirements:**
- All 6 state stores operational and queryable
- 3 Kafka topics consumed: shipment.events, vehicle.telemetry, warehouse.operations
- Real-time shipment tracking by status, customer, and late detection
- Vehicle state tracking with position and load information
- Windowed warehouse metrics (15-min tumbling window)
- Windowed delivery performance (1-hour hopping window with 30-min advance)
- Interactive Queries API with 12 endpoints on port 7070
- Query API with 14 REST endpoints on port 8080

✅ **Technical Requirements:**
- JsonSerde for custom state store value serialization
- WindowStore implementations with 6-hour retention
- Late shipment detection with 30-minute grace period
- Multi-instance query support for all 6 state stores
- Parallel query aggregation across distributed state

✅ **State Stores Implemented:**
1. `active-shipments-by-status` - Count by ShipmentEventType (KeyValue)
2. `vehicle-current-state` - Latest telemetry per vehicle (KeyValue)
3. `shipments-by-customer` - Aggregated stats per customer (KeyValue)
4. `late-shipments` - Shipments past expected delivery (KeyValue)
5. `warehouse-realtime-metrics` - 15-min operation metrics (Windowed)
6. `hourly-delivery-performance` - 1-hour delivery stats (Windowed)

## Phase 4 Success Metrics (All Achieved)

✅ **Functional Requirements:**
- 9 Kafka Streams state stores operational (6 original + 3 order stores)
- order.status topic consumed with order tracking state stores
- PostgreSQL reference data accessible via 17 REST endpoints
- Hybrid queries combining Kafka Streams + PostgreSQL data
- Graceful error handling with data quality warnings

✅ **Technical Requirements:**
- Quarkus reactive PostgreSQL client with Mutiny Uni<T>
- HybridQueryResult wrapper with sources and warnings metadata
- QueryOrchestrationService for multi-source query orchestration
- Try-catch blocks around all Kafka Streams calls with fallback
- Builder pattern for enriched model classes

✅ **New State Stores (Phase 4):**
7. `order-current-state` - Current state per order (KeyValue)
8. `orders-by-customer` - Aggregated order stats per customer (KeyValue)
9. `order-sla-tracking` - Orders at SLA risk (KeyValue)

✅ **New API Endpoints (Phase 4):**
- 17 reference data endpoints (`/api/reference/*`)
- 7 hybrid query endpoints (`/api/hybrid/*`)
- 6 order state query endpoints (`/api/orders/*`)

✅ **Key Files Created:**
- `query-api/src/main/java/com/smartship/api/services/PostgresQueryService.java`
- `query-api/src/main/java/com/smartship/api/services/QueryOrchestrationService.java`
- `query-api/src/main/java/com/smartship/api/ReferenceDataResource.java`
- `query-api/src/main/java/com/smartship/api/HybridQueryResource.java`
- `query-api/src/main/java/com/smartship/api/model/hybrid/*.java` (enriched models)
- `query-api/src/main/java/com/smartship/api/model/reference/*.java` (DTOs)

## Phase 5 Success Metrics (All Achieved)

✅ **Native Image Build:**
- GraalVM/Mandrel native image compiles successfully
- Startup time <100ms (vs ~10s JVM mode)
- Memory footprint 64-128Mi (vs 256-512Mi JVM mode)
- Container image size ~50MB (vs ~200MB JVM mode)
- Faster Kubernetes startup/liveness probes

✅ **Test Coverage:**
- 5 test classes covering major services and REST endpoints
- PostgresQueryService unit tests with mocked database
- KafkaStreamsQueryService unit tests with mocked HTTP client
- ReferenceDataResource REST endpoint integration tests
- QueryResource REST endpoint integration tests
- HybridQueryResource REST endpoint integration tests

✅ **Exception Handling:**
- Consistent JSON error responses across all endpoints
- NotFoundException handled with proper HTTP 404 status
- Error responses include message, status code, and timestamp

✅ **Key Files Created (Phase 5):**
- `query-api/src/main/java/com/smartship/api/config/NativeImageReflectionConfig.java` - 23 classes registered
- `query-api/src/main/java/com/smartship/api/config/ExceptionMappers.java` - JSON error responses
- `query-api/src/test/java/com/smartship/api/services/PostgresQueryServiceTest.java`
- `query-api/src/test/java/com/smartship/api/services/KafkaStreamsQueryServiceTest.java`
- `query-api/src/test/java/com/smartship/api/ReferenceDataResourceTest.java`
- `query-api/src/test/java/com/smartship/api/QueryResourceTest.java`
- `query-api/src/test/java/com/smartship/api/HybridQueryResourceTest.java`

✅ **Build & Deployment:**
- `scripts/02-build-all.py` supports `--native` flag for native image builds
- `query-api.yaml` includes native image resource configurations
- Native profile in `query-api/pom.xml` with Java 21 for GraalVM compatibility
