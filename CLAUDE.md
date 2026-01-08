# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## MCP Tools

Always use context7 when I need code generation, setup or configuration steps, or library/API documentation. 
This means you should automatically use the Context7 MCP tools to resolve library id and get library docs without me having to explicitly ask.

## Project Overview

SmartShip Logistics is a real-time event streaming demonstration showcasing Kafka Streams, materialized views, and an LLM-queryable API for a regional logistics and fulfillment company. The project is implemented as a Maven multi-module monorepo deployed on Kubernetes (minikube).

**Current Status:** Phase 1-5 ✅ COMPLETED | Phase 6 ✅ COMPLETED (LLM chatbot with LangChain4j + Ollama) | Phase 7-8 Pending

**Detailed Walkthrough:** See `docs/index.md` for in-depth documentation with architecture diagrams and query flow examples.

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

### Phase 6: LLM Chatbot Architecture
The Query API includes an LLM chatbot for natural language queries:

```
Query-API
    ├── ChatResource → /api/chat endpoint
    ├── LogisticsAssistant → @RegisterAiService AI interface
    ├── ShipmentTools → 3 @Tool methods for shipment queries
    ├── CustomerTools → 2 @Tool methods for customer queries
    └── SessionChatMemoryProvider → In-memory session storage
```

**LLM Providers (configurable via LLM_PROVIDER env var):**
- **Ollama** (default): Local LLM with llama3.2 model
- **OpenAI**: Cloud LLM (requires OPENAI_API_KEY)
- **Anthropic**: Cloud LLM (requires ANTHROPIC_API_KEY)

**Chat Endpoints:**
- `POST /api/chat` - Send chat message, get AI response
- `GET /api/chat/health` - Check LLM service health
- `GET /api/chat/sessions/count` - Get active session count
- `DELETE /api/chat/sessions/{sessionId}` - Clear session

**Environment Variables:**
- `LLM_PROVIDER`: ollama (default), openai, or anthropic
- `OLLAMA_BASE_URL`: Ollama service URL (default: http://ollama.smartship.svc.cluster.local:11434)
- `OPENAI_API_KEY`: OpenAI API key (optional)
- `ANTHROPIC_API_KEY`: Anthropic API key (optional)

**Key Files (Phase 6):**
- `query-api/src/main/java/com/smartship/api/ai/LogisticsAssistant.java`
- `query-api/src/main/java/com/smartship/api/ai/ChatResource.java`
- `query-api/src/main/java/com/smartship/api/ai/tools/ShipmentTools.java`
- `query-api/src/main/java/com/smartship/api/ai/tools/CustomerTools.java`
- `kubernetes/infrastructure/ollama.yaml`

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
# Without Ollama (Phase 1-5):
minikube start --cpus=4 --memory=12288 --disk-size=50g

# With Ollama for LLM chat (Phase 6+):
minikube start --cpus=6 --memory=16384 --disk-size=80g

# 1. Setup infrastructure (Strimzi, Kafka KRaft, Apicurio, PostgreSQL, Ollama)
python3 scripts/01-setup-infra.py

# Or with pre-loaded models (faster startup, avoids in-cluster download):
python3 scripts/01-setup-infra.py --models llama3.2

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

# Pattern: /state/{storeName} or /state/{storeName}/{key}
curl http://localhost:7070/state/active-shipments-by-status | jq
curl http://localhost:7070/state/vehicle-current-state/VEH-001 | jq

# Multi-instance metadata
curl http://localhost:7070/metadata/instances/active-shipments-by-status | jq
```

**All 9 state stores:** `active-shipments-by-status`, `vehicle-current-state`, `shipments-by-customer`, `late-shipments`, `warehouse-realtime-metrics`, `hourly-delivery-performance`, `order-current-state`, `orders-by-customer`, `order-sla-tracking`

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

### Query via REST API (44+ endpoints)
```bash
kubectl port-forward svc/query-api 8080:8080 -n smartship &

# Key examples (see Swagger UI for full API)
curl http://localhost:8080/api/shipments/status/all | jq
curl http://localhost:8080/api/vehicles/state/VEH-001 | jq
curl http://localhost:8080/api/reference/warehouses | jq
curl http://localhost:8080/api/hybrid/customers/CUST-0001/overview | jq

# Health and OpenAPI
curl http://localhost:8080/api/health | jq
open http://localhost:8080/swagger-ui
```

**Endpoint groups:** `/api/shipments/*`, `/api/vehicles/*`, `/api/customers/*`, `/api/warehouses/*`, `/api/performance/*`, `/api/orders/*`, `/api/reference/*`, `/api/hybrid/*`, `/api/chat/*`

### ID Formats (Critical for Queries)
Use these exact formats when querying:
- **Customers:** `CUST-0001` through `CUST-0200` (4 digits, zero-padded)
- **Vehicles:** `VEH-001` through `VEH-050` (3 digits)
- **Drivers:** `DRV-001` through `DRV-075` (3 digits)
- **Warehouses:** `WH-RTM`, `WH-FRA`, `WH-BCN`, `WH-WAW`, `WH-STO`

### View Kafka Events
```bash
# List topics
kubectl exec -it events-cluster-dual-role-0 -n smartship -- \
  bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# View events (replace TOPIC with: shipment.events, vehicle.telemetry, warehouse.operations, order.status)
kubectl exec -it events-cluster-dual-role-0 -n smartship -- \
  bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic TOPIC --from-beginning --max-messages 5
```

### PostgreSQL Access
```bash
kubectl port-forward svc/postgresql 5432:5432 -n smartship &
psql -h localhost -U smartship -d smartship -c "SELECT * FROM warehouses;"
```

**Tables:** warehouses (5), customers (200), vehicles (50), products (10K), drivers (75), routes (100) - see `kubernetes/infrastructure/init.sql`

### Query Chat API (LLM)
```bash
kubectl port-forward svc/query-api 8080:8080 -n smartship &

# Chat with LLM
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "How many shipments are in transit?"}'

# Check chat health
curl http://localhost:8080/api/chat/health

# Get active session count
curl http://localhost:8080/api/chat/sessions/count
```

**Example Queries:**
1. "How many shipments are currently in transit?"
2. "Which shipments are delayed?"
3. "Show me the shipment stats for customer CUST-0001"
4. "Find customers with 'Tech' in their name"
5. "Give me an overview of customer CUST-0050"

## Key Technology Versions

**Critical:** These specific versions are required for compatibility:
- **Java:** 25 LTS (eclipse-temurin:25-jdk-ubi10-minimal base image)
- **Kafka:** 4.1.1 (clients + streams)
- **Strimzi:** 0.49.0 (for Kafka 4.1.1 KRaft support)
- **Avro:** 1.12.1
- **Apicurio Registry:** 3.1.4
- **Quarkus:** 3.30.1
- **Quarkus LangChain4j:** 1.5.0.CR2 (LLM integration)
- **PostgreSQL:** 15 (postgres:15-alpine)
- **SLF4J:** 2.0.17
- **Logback:** 1.5.12
- **Jib Maven Plugin:** 3.5.1

All versions are centralized in parent `pom.xml` properties.

## Data Generators Architecture

The `data-generators` module contains 4 generators coordinated by `DataCorrelationManager`:

**Key Components:**
- **ReferenceDataLoader** - Loads reference data from PostgreSQL at startup (retry logic, single source of truth)
- **DataCorrelationManager** - Singleton ensuring referential integrity across generators
- **GeneratorMain** - Entry point starting all 4 generator threads

**Environment Variables:** `POSTGRES_HOST`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`

| Generator | Topic | Rate |
|-----------|-------|------|
| ShipmentEventGenerator | shipment.events | 50-80/sec |
| VehicleTelemetryGenerator | vehicle.telemetry | 20-30/sec |
| WarehouseOperationGenerator | warehouse.operations | 15-25/sec |
| OrderStatusGenerator | order.status | 10-15/sec |

## Avro Schemas (Phase 2)

Four schemas in `schemas/src/main/avro/`:

| Schema | Key Fields | Enums |
|--------|------------|-------|
| `shipment-event.avsc` | shipment_id, customer_id, warehouse_id, destination_city | ShipmentEventType (9 values) |
| `vehicle-telemetry.avsc` | vehicle_id, location (nested), current_load (nested) | VehicleStatus (5 values) |
| `warehouse-operation.avsc` | event_id, warehouse_id, operation_type, shipment_id (nullable) | OperationType (7 values) |
| `order-status.avsc` | order_id, customer_id, shipment_ids (array), priority | OrderStatusType (7), OrderPriority (4) |

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

| Phase | Status | Summary |
|-------|--------|---------|
| 1 | ✅ | Minimal e2e: 1 topic, 1 state store, multi-instance StatefulSet |
| 2 | ✅ | All 4 topics, 4 generators, DataCorrelationManager, 6 PostgreSQL tables |
| 3 | ✅ | 6 state stores (4 KeyValue + 2 Windowed), 14 REST endpoints |
| 4 | ✅ | 9 state stores, PostgreSQL integration, 44+ endpoints, hybrid queries |
| 5 | ✅ | Native image (<100ms startup), tests, exception handling |
| 6 | ✅ | LLM chatbot with LangChain4j 1.5.0.CR2, Ollama, multi-provider support |
| 7-8 | Pending | Advanced LLM features: guardrails, streaming, observability |

See `design/implementation-plan.md` for detailed phase breakdown.

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