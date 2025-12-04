# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## MCP Tools

Always use context7 when I need code generation, setup or configuration steps, or library/API documentation. 
This means you should automatically use the Context7 MCP tools to resolve library id and get library docs without me having to explicitly ask.

## Project Overview

SmartShip Logistics is a real-time event streaming demonstration showcasing Kafka Streams, materialized views, and an LLM-queryable API for a regional logistics and fulfillment company. The project is implemented as a Maven multi-module monorepo deployed on Kubernetes (minikube).

**Current Status:** Phase 1 ✅ COMPLETED (1 topic, 1 state store, basic query capability)

## Critical Architecture Concepts

### Event Flow Architecture
```
Data Generator → Kafka Topic → Kafka Streams Processor → State Store → Query API
     (Avro)      (shipment.    (LogisticsTopology)    (Interactive   (Quarkus)
                  events)                              Queries)
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

**Critical Properties:**
```properties
# Java 25 base image for container builds
quarkus.jib.base-jvm-image=eclipse-temurin:25-jdk-ubi10-minimal

# Streams processor connection
streams-processor.url=http://streams-processor.smartship.svc.cluster.local:7070
```

### Kafka Streams State Store Pattern
The streams processor (`streams-processor/`) creates materialized KTables that are queryable via Interactive Queries:
1. **Topology** (`LogisticsTopology.java`): Defines stream processing logic and state stores
2. **Interactive Query Server** (`InteractiveQueryServer.java`): Exposes HTTP endpoints on port 7070 for direct state store queries
3. **Query API** (`query-api/`): Quarkus REST service that calls the Interactive Query Server

**Important:** State stores are named constants (e.g., `STATE_STORE_NAME = "active-shipments-by-status"`). Use these constants when adding new query endpoints.

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
- `wait_for_condition()` - wait for K8s resource readiness
- `setup_container_runtime()` - podman/docker detection
- `get_minikube_env()` - container runtime environment setup

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
- `kubernetes/base/` - Common resources
- `kubernetes/overlays/minikube/` - Laptop-optimized (~1.5 CPU, ~3.5Gi memory)
- `kubernetes/overlays/cloud/` - Cloud-optimized (Phase 5+)

Deploy with: `kubectl apply -k kubernetes/overlays/minikube`

## Debugging and Monitoring

### Check Deployment Status
```bash
kubectl get pods -n smartship
# Expected: events-cluster-dual-role-0, apicurio-registry-*, postgresql-0,
#           data-generators-*, streams-processor-*, query-api-*
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
kubectl exec -it events-cluster-dual-role-0 -n smartship -- \
  bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic shipment.events \
  --from-beginning \
  --max-messages 10
```

### PostgreSQL Access
```bash
kubectl port-forward svc/postgresql 5432:5432 -n smartship &
psql -h localhost -U smartship -d smartship -c "SELECT * FROM warehouses;"
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

## Adding New Event Types (Future Phases)

When implementing Phase 2+ (additional topics and generators):

1. **Create Avro schema** in `schemas/src/main/avro/`
2. **Add topic definition** in `kubernetes/base/kafka-topic.yaml` (KafkaTopic CR)
3. **Create generator** in `data-generators/src/main/java/com/smartship/generators/`
4. **Extend LogisticsTopology** in `streams-processor/` with new state stores
5. **Add Interactive Query endpoints** in `InteractiveQueryServer.java`
6. **Add REST endpoints** in `query-api/src/main/java/com/smartship/api/`
7. **Rebuild in order:** schemas → common → other modules

## Phase Information

**Phase 1 (✅ COMPLETED):** Minimal end-to-end with 1 topic, 1 state store
- Topic: `shipment.events` (CREATED → IN_TRANSIT → DELIVERED)
- State store: `active-shipments-by-status` (count by status)
- Generator: Simple lifecycle every 6 seconds

**Phase 2-6 (PENDING):** See `design/implementation-plan.md` for detailed roadmap:
- Phase 2: Add 3 more topics (vehicle telemetry, warehouse operations, order status)
- Phase 3: Add 5 more state stores (6 total)
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
kubectl logs deployment/streams-processor -n smartship | grep "Updated count"
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