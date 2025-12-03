# SmartShip Logistics Implementation Plan

## Overview

This plan implements the synthetic data proposal for a regional logistics and fulfillment company with real-time event streaming, materialized views, and an LLM-queryable API.

**Target Architecture:**
- Apache Kafka (via Strimzi Operator) for event streaming
- Apicurio Registry for Avro schema management
- Kafka Streams for real-time materialized views
- PostgreSQL for reference data
- Quarkus REST API for LLM queries (with GraalVM native image)
- All components running on Kubernetes (minikube or cloud)

## Project Structure: Maven Monorepo

**Decision:** Monorepo with Maven multi-module for centralized schema management and unified builds.

```
realtime-context-demo/
├── pom.xml                          # Parent POM
├── schemas/                         # Avro schemas → generates Java classes
├── common/                          # Shared utilities (KafkaConfig, GeoUtils)
├── database/                        # PostgreSQL DDL and seed data scripts
├── data-generators/                 # Synthetic event producers (4 generators)
├── streams-processor/               # Kafka Streams with 6 materialized views
├── query-api/                       # Quarkus REST API for LLM (native image)
├── kubernetes/
│   ├── base/                        # Common Kubernetes resources
│   └── overlays/
│       ├── minikube/                # Laptop-friendly (~1.85 CPU, ~4.6Gi RAM)
│       └── cloud/                   # Cloud-optimized (auto-scaling)
└── tests/                           # Integration tests
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

**Critical file:** `streams-processor/src/main/java/com/smartship/streams/topology/LogisticsTopology.java`

### 5. Query API (Quarkus)
**Purpose:** REST API for LLM to query real-time and reference data

**Technology:** Quarkus 3.30.x (Supersonic Subatomic Java)

**Why Quarkus:**
- ✓ **Fast startup:** <100ms (vs. ~10s for Spring Boot)
- ✓ **Low memory:** ~30MB RSS with native image (vs. ~200MB JVM)
- ✓ **Kubernetes-native:** Built for containers and cloud
- ✓ **Developer joy:** Live reload, dev services, unified config
- ✓ **Native compilation:** GraalVM for minimal container images

**Three service layers:**

1. **KafkaStreamsQueryService**
   - HTTP client to Kafka Streams Interactive Queries (port 7070)
   - Queries all 6 state stores

2. **PostgresQueryService**
   - Quarkus Reactive PostgreSQL client (Mutiny)
   - Queries 6 reference tables: customers, warehouses, vehicles, products, drivers, routes

3. **QueryOrchestrationService**
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
    <artifactId>quarkus-container-image-jib</artifactId>
  </dependency>
</dependencies>
```

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

**Critical file:** `database/schema/init.sql`

## Technology Stack

### Core Technologies
- **Java:** 17 LTS (records, pattern matching, GraalVM native image support)
- **Build Tool:** Maven 3.9+ (superior Avro plugin, simpler multi-module)
- **Kafka:** 3.7.0 (clients + streams)
- **Avro:** 1.12.1
- **Apicurio Registry:** 3.0.0.M4 (Avro Serdes)
- **Quarkus:** 3.20.x (REST API, Kafka integration, reactive PostgreSQL)
- **PostgreSQL:** 15+
- **Containerization:** Quarkus Native Image + Jib (for non-native Java builds)

### Maven Plugins
- **avro-maven-plugin** - Generate Java from .avsc files
- **jib-maven-plugin** - Build optimized container images (data-generators, streams-processor)
- **quarkus-maven-plugin** - Build Quarkus applications and native images
- **maven-compiler-plugin** - Java 17 compilation
- **maven-surefire-plugin** - Unit tests
- **maven-failsafe-plugin** - Integration tests

## Kubernetes Deployment Strategy

### Organization: Kustomize
**Decision:** Kustomize (not Helm) for simpler YAML-based configuration

```
kubernetes/
├── base/                           # Common resources
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   ├── kafka/                      # Strimzi Kafka CR
│   ├── apicurio/                   # ApicurioRegistry CR
│   ├── postgresql/                 # StatefulSet
│   ├── data-generators/            # Deployment
│   ├── streams-processor/          # Deployment
│   └── query-api/                  # Deployment + Service
└── overlays/
    ├── minikube/                   # Laptop-friendly
    │   ├── kustomization.yaml
    │   └── resource-limits-patch.yaml
    └── cloud/                      # Cloud-optimized
        ├── kustomization.yaml
        ├── resource-scaling-patch.yaml
        └── storage-class-patch.yaml
```

**Deploy:**
```bash
kubectl apply -k kubernetes/overlays/minikube
kubectl apply -k kubernetes/overlays/cloud
```

### Deployment Order

**Phase 1: Operators** (prerequisites)
1. Strimzi Kafka Operator
2. Apicurio Registry Operator

**Phase 2: Infrastructure**
3. PostgreSQL StatefulSet
4. Kafka cluster (via Strimzi Kafka CR)
5. Apicurio Registry (via ApicurioRegistry CR)
6. Kafka Topics (4 KafkaTopic CRDs)

**Phase 3: Seed Data**
7. Kubernetes Job to load PostgreSQL seed data

**Phase 4: Applications**
8. Data Generators Deployment
9. Streams Processor Deployment
10. Query API Deployment + Service

### Resource Allocation

#### Minikube (Laptop)
**Minikube setup:** `minikube start --cpus=4 --memory=12288 --disk-size=50g`

**Total resources:** ~1.85 CPU, ~4.6Gi memory (vs. ~2.05 CPU, ~5Gi with Spring Boot)

| Component | CPU Request | CPU Limit | Memory Request | Memory Limit | Replicas |
|-----------|------------|-----------|----------------|--------------|----------|
| Kafka Broker | 500m | 1000m | 1Gi | 2Gi | 1 |
| ZooKeeper | 200m | 500m | 512Mi | 1Gi | 1 |
| Apicurio Registry | 200m | 500m | 512Mi | 1Gi | 1 |
| PostgreSQL | 250m | 500m | 512Mi | 1Gi | 1 |
| Data Generators | 200m | 500m | 512Mi | 1Gi | 1 |
| Streams Processor | 500m | 1000m | 1Gi | 2Gi | 1 |
| Query API (native) | 100m | 250m | 64Mi | 128Mi | 1 |

**Storage:**
- Kafka: 10Gi PV
- ZooKeeper: 5Gi PV
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

## Development Workflow

### Local Setup (Quick Start)
```bash
# Prerequisites: Java 17, Maven 3.9+, Docker, minikube, kubectl

# 1. Start minikube
minikube start --cpus=4 --memory=12288 --disk-size=50g

# 2. Create namespace
kubectl create namespace smartship

# 3. Install operators
kubectl create -f 'https://strimzi.io/install/latest?namespace=smartship'
kubectl apply -f https://raw.githubusercontent.com/Apicurio/apicurio-registry-operator/main/install/install.yaml

# 4. Deploy infrastructure
kubectl apply -k kubernetes/overlays/minikube

# 5. Build and deploy applications
eval $(minikube docker-env)  # Point to minikube Docker
mvn clean install
mvn compile jib:dockerBuild   # Build all Docker images

# 6. Build Query API native image
cd query-api
./mvnw package -Pnative -Dquarkus.native.container-build=true

# 7. Deploy applications
kubectl apply -k kubernetes/overlays/minikube

# 8. Watch deployment
kubectl get pods -n smartship -w
```

### Build Commands
```bash
# Build everything
mvn clean install

# Build individual module
cd data-generators && mvn clean package

# Run unit tests
mvn test

# Run integration tests
mvn verify -P integration-tests

# Build Docker images (Jib for non-native)
mvn compile jib:dockerBuild

# Build Quarkus Query API (native image)
cd query-api
./mvnw package -Pnative -Dquarkus.native.container-build=true
# Output: Docker image with native executable (~50MB, <100ms startup)

# Build Quarkus Query API (JVM mode for dev)
cd query-api
./mvnw package
# Output: Standard JVM image (faster builds during development)
```

### Testing and Validation
```bash
# Check Kafka topics
kubectl exec -it kafka-cluster-kafka-0 -n smartship -- \
  bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# Monitor event production
kubectl exec -it kafka-cluster-kafka-0 -n smartship -- \
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

### Phase 1: Minimal End-to-End (Days 1-7)
**Goal:** Working system with 1 topic, 1 state store, basic query capability

**Scope:**
- **Infrastructure:** Kafka (1 broker), Apicurio, PostgreSQL (1 table only)
- **Schema:** 1 Avro schema (shipment.events only)
- **Generator:** 1 simple generator (basic shipment events)
- **Streams:** 1 state store (active-shipments-by-status)
- **Query API:** 1 endpoint (query shipments by status)
- **Database:** 1 table (warehouses - just 5 rows for reference)

**Tasks:**
1. Create Maven parent POM with minimal modules (schemas, common, data-generators, streams-processor, query-api)
2. Define 1 Avro schema: shipment-event.avsc (simplified version)
3. Create common module with basic KafkaConfig + ApicurioConfig
4. Create PostgreSQL schema with 1 table (warehouses)
5. Create Kubernetes base manifests (Kafka, Apicurio, PostgreSQL)
6. Create Kustomize minikube overlay
7. Deploy infrastructure to minikube
8. Implement minimal ShipmentEventGenerator (creates CREATED → DELIVERED events only)
9. Implement minimal Kafka Streams topology (1 state store: active-shipments-by-status)
10. Implement minimal Quarkus Query API (1 endpoint: GET /shipments/by-status/{status})
11. Deploy all components
12. Validate end-to-end: generator → Kafka → Streams → Query API

**Deliverables:**
- ✓ Full stack running on minikube
- ✓ 1 generator producing shipment events
- ✓ 1 Kafka Streams state store populated
- ✓ 1 Query API endpoint returning data
- ✓ Can query: "Show all IN_TRANSIT shipments"

### Phase 2: Add Remaining Topics & Generators (Days 8-11)
**Goal:** All 4 topics producing events, with data correlation

**Scope:**
- Add 3 more schemas: vehicle-telemetry, warehouse-operation, order-status
- Add 3 more generators: VehicleTelemetry, WarehouseOperation, OrderStatus
- Implement DataCorrelationManager for referential integrity
- Add 3 more PostgreSQL tables: vehicles, products, customers (with seed data)

### Phase 3: Complete Kafka Streams State Stores (Days 12-15)
**Goal:** All 6 materialized views operational

**Scope:**
- Add 5 more state stores (vehicle-current-state, shipments-by-customer, warehouse-realtime-metrics, late-shipments, hourly-delivery-performance)
- Implement windowed aggregations
- Enable Interactive Queries API

### Phase 4: Complete Query API (Days 16-18)
**Goal:** Full LLM query capability with multi-source queries

**Scope:**
- Add /api/query/reference and /api/query/hybrid endpoints
- Implement full orchestration with Mutiny reactive streams
- Add OpenAPI documentation (automatic with Quarkus)
- Build native image and validate <100ms startup

### Phase 5: Refinement & Production-Ready (Days 19-23)
**Goal:** Production-quality implementation with docs and testing

**Scope:**
- Add advanced features (exception injection, time-based event rates, realistic geography)
- Create Kustomize cloud overlay with HPA
- Comprehensive testing (unit, integration, performance)
- Complete documentation (README, deployment guides, troubleshooting)

### Phase 6: Demo Optimization & Polish (Days 24-25)
**Goal:** Demo-ready with example queries and presentations

**Deliverables:**
- Demo-ready system
- Sample LLM query scripts
- Presentation materials

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
5. **query-api/src/main/java/com/smartship/api/QueryResource.java** - Quarkus JAX-RS endpoint
6. **kubernetes/overlays/minikube/kustomization.yaml** - Minikube deployment
7. **database/schema/init.sql** - PostgreSQL DDL

## Next Steps

After approval of this plan, implementation will proceed in the 6 phases outlined above, starting with Phase 1 (Minimal End-to-End) to establish a working demo within 1 week.
