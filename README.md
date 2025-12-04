# SmartShip Logistics - Real-Time Context Demo

A real-time event streaming demonstration for a regional logistics and fulfillment company, showcasing Kafka Streams, materialized views, and an LLM-queryable API.

## Phase 1: Minimal End-to-End Implementation

**Status:** ✅ Complete (with multi-instance support)
**Goal:** Working vertical slice with 1 topic, 1 state store, basic query capability, and horizontal scaling

## 🏗️ Architecture

```
┌─────────────────┐
│ Data Generators │──┐
│  (Shipments)    │  │
└─────────────────┘  │
                     ▼
              ┌──────────────┐
              │ Kafka (KRaft)│
              │ shipment.    │
              │   events     │
              └──────┬───────┘
                     │
                     ▼
         ┌─────────────────────┐
         │ Kafka Streams       │
         │ Processor           │
         │ (StatefulSet)       │
         │                     │
         │ State Store:        │
         │ active-shipments-   │
         │  by-status          │
         │                     │
         │ Pods: 0, 1, 2...    │
         └─────────┬───────────┘
                   │
                   ▼
         ┌─────────────────┐
         │ Query API       │
         │ (Quarkus)       │
         │                 │
         │ Instance        │
         │ Discovery →     │
         │ Parallel Queries│
         └─────────────────┘
```

## 🚀 Technology Stack

- **Java 25 LTS** - Programming language (eclipse-temurin:25-jdk-ubi10-minimal)
- **Kafka 4.1.1** - Event streaming with KRaft (no ZooKeeper)
- **Kafka Streams 4.1.1** - Real-time stream processing
- **Avro 1.12.1** - Schema-based serialization
- **Apicurio Registry 3.1.4** - Schema registry
- **Quarkus 3.30.1** - REST API framework (JVM mode)
- **PostgreSQL 15** - Reference data storage (postgres:15-alpine)
- **Strimzi 0.49.0** - Kafka operator for Kubernetes
- **SLF4J 2.0.17** - Logging abstraction
- **Logback 1.5.12** - Logging implementation
- **Jib Maven Plugin 3.5.1** - Container image builder
- **Kustomize** - Kubernetes manifest management
- **Python 3.9+** - Deployment automation
- **Podman/Docker** - Container runtime

## 📋 Prerequisites

### Required Tools
- Java 25 LTS (or compatible JDK)
- Maven 3.9+
- Podman or Docker CLI
- Minikube
- kubectl
- Git
- Python 3.9+

### Start Minikube
```bash
minikube start --cpus=4 --memory=12288 --disk-size=50g
```

## 🎯 Quick Start

### 1. Setup Infrastructure (Kafka, Apicurio, PostgreSQL)
```bash
python3 scripts/01-setup-infra.py
```

This will:
- Install Strimzi Kafka Operator (0.49.0)
- Deploy Kafka cluster with KRaft (single node)
- Deploy Apicurio Registry
- Deploy PostgreSQL with warehouse data
- Create Kafka topic: `shipment.events`

### 2. Build All Modules
```bash
# Optional: Set container runtime (default: podman)
export CONTAINER_RUNTIME=podman  # or docker

python3 scripts/02-build-all.py
```

This will:
- Build Java modules (schemas, common, data-generators, streams-processor)
- Build Quarkus query-api (JVM mode)
- Create container images
- Load images into minikube

### 3. Deploy Applications
```bash
python3 scripts/03-deploy-apps.py
```

This will:
- Deploy data-generators
- Deploy streams-processor
- Deploy query-api
- Wait for all pods to be ready

### 4. Validate Deployment
```bash
python3 scripts/04-validate.py
```

This will:
- Check Kafka cluster status
- Verify PostgreSQL data
- Test event generation
- Query state store
- Test Query API endpoints

### 5. Cleanup (when done)
```bash
python3 scripts/05-cleanup.py
```

## 🔍 Testing the System

### Check Pods
```bash
kubectl get pods -n smartship
```

Expected output:
- `events-cluster-dual-role-0` - Kafka broker (KRaft mode)
- `apicurio-registry-...` - Schema registry
- `postgresql-0` - Database
- `data-generators-...` - Event producer
- `streams-processor-0` - Kafka Streams app (StatefulSet)
- `query-api-...` - REST API

```bash
# Check StatefulSet status
kubectl get statefulset -n smartship
```

### Monitor Event Generation
```bash
kubectl logs -f deployment/data-generators -n smartship
```

### Query State Store (Interactive Queries)
```bash
kubectl port-forward svc/streams-processor 7070:7070 -n smartship &

# Get count for specific status
curl http://localhost:7070/state/active-shipments-by-status/IN_TRANSIT | jq

# Get all status counts
curl http://localhost:7070/state/active-shipments-by-status | jq

# Query StreamsMetadata (multi-instance support)
curl http://localhost:7070/metadata/instances/active-shipments-by-status | jq
curl http://localhost:7070/metadata/instance-for-key/active-shipments-by-status/IN_TRANSIT | jq
```

### Scale Streams Processor (Multi-Instance)
```bash
# Scale to 3 replicas
kubectl scale statefulset streams-processor -n smartship --replicas=3

# Verify all pods are ready
kubectl get pods -l app=streams-processor -n smartship

# Check APPLICATION_SERVER env var
kubectl exec streams-processor-0 -n smartship -- printenv APPLICATION_SERVER
```

### Query via REST API
```bash
kubectl port-forward svc/query-api 8080:8080 -n smartship &

# Get shipments by status
curl http://localhost:8080/api/shipments/by-status/CREATED | jq
curl http://localhost:8080/api/shipments/by-status/IN_TRANSIT | jq
curl http://localhost:8080/api/shipments/by-status/DELIVERED | jq

# Get all status counts
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

### Check PostgreSQL Data
```bash
kubectl port-forward svc/postgresql 5432:5432 -n smartship &
psql -h localhost -U smartship -d smartship -c "SELECT * FROM warehouses;"
```

## 📊 What's Happening

### Event Flow
1. **Data Generator** creates shipment lifecycles every 6 seconds
   - Generates: CREATED → IN_TRANSIT → DELIVERED
   - Random warehouse selection from 5 European locations
   - Events published to `shipment.events` Kafka topic

2. **Kafka Streams Processor** (StatefulSet) consumes events and maintains state
   - Groups events by status
   - Counts shipments per status
   - Stores in materialized view: `active-shipments-by-status`
   - Exposes Interactive Queries API on port 7070
   - Supports horizontal scaling with state partitioning
   - Each pod has stable identity via headless service

3. **Query API** provides REST endpoints with multi-instance support
   - Discovers streams-processor instances via DNS
   - Routes specific key queries to correct instance
   - Aggregates results from all instances for aggregate queries
   - Uses parallel queries with CompletableFuture
   - Caches instance metadata for 30 seconds
   - Returns JSON responses
   - OpenAPI documentation available

## 🏗️ Project Structure

```
realtime-context-demo/
├── pom.xml                          # Parent POM
├── schemas/                         # Avro schemas
│   └── src/main/avro/
│       └── shipment-event.avsc
├── common/                          # Shared utilities
│   └── src/main/java/com/smartship/common/
│       ├── KafkaConfig.java
│       └── ApicurioConfig.java
├── database/                        # PostgreSQL schemas
│   └── schema/init.sql
├── data-generators/                 # Event producers
│   └── src/main/java/.../ShipmentEventGenerator.java
├── streams-processor/               # Kafka Streams (StatefulSet)
│   └── src/main/java/com/smartship/streams/
│       ├── LogisticsTopology.java
│       ├── StreamsApplication.java
│       ├── InteractiveQueryServer.java
│       └── StreamsMetadataResponse.java
├── query-api/                       # Quarkus REST API
│   └── src/main/java/com/smartship/api/
│       ├── QueryResource.java
│       ├── KafkaStreamsQueryService.java
│       ├── model/StreamsInstanceMetadata.java
│       └── services/StreamsInstanceDiscoveryService.java
├── kubernetes/                      # K8s manifests
│   ├── base/
│   ├── applications/                # Application manifests
│   │   ├── data-generators.yaml
│   │   ├── streams-processor.yaml   # StatefulSet + Headless Service
│   │   └── query-api.yaml
│   └── overlays/minikube/
└── scripts/                         # Python automation
    ├── common.py
    ├── 01-setup-infra.py
    ├── 02-build-all.py
    ├── 03-deploy-apps.py
    ├── 04-validate.py
    └── 05-cleanup.py
```

## 📝 Data Model

### ShipmentEvent (Avro Schema)
```json
{
  "shipment_id": "SH-ABC12345",
  "warehouse_id": "WH-RTM",
  "event_type": "IN_TRANSIT",
  "timestamp": 1701234567890
}
```

### Warehouses (PostgreSQL)
- WH-RTM: Rotterdam Distribution Center (Netherlands)
- WH-FRA: Frankfurt Logistics Hub (Germany)
- WH-BCN: Barcelona Fulfillment Center (Spain)
- WH-WAW: Warsaw Regional Depot (Poland)
- WH-STO: Stockholm Nordic Hub (Sweden)

## 🐛 Troubleshooting

### Pods not starting
```bash
# Check pod status
kubectl describe pod <pod-name> -n smartship

# Check logs
kubectl logs <pod-name> -n smartship
```

### Kafka cluster not ready
```bash
# Check Kafka status
kubectl get kafka events-cluster -n smartship -o yaml

# Check Strimzi operator logs
kubectl logs deployment/strimzi-cluster-operator -n smartship
```

### Images not found
```bash
# Verify images in minikube
minikube image ls | grep smartship

# Rebuild and reload
python3 scripts/02-build-all.py
```

### Container runtime issues
```bash
# Verify runtime
podman --version  # or docker --version

# Set explicitly
export CONTAINER_RUNTIME=podman  # or docker
python3 scripts/02-build-all.py
```

### Query API pod restarting
If the query-api pod keeps restarting with health check failures:
```bash
# Check pod status
kubectl describe pod -l app=query-api -n smartship

# Check for HTTP 404 errors on /q/health/live or /q/health/ready
kubectl logs deployment/query-api -n smartship
```

**Solution:** Ensure `quarkus-smallrye-health` dependency is in `query-api/pom.xml` and the container image uses Java 25 base image. See CLAUDE.md for detailed fix.

## 🔧 Development

### Build Individual Modules
```bash
# Build schemas only
mvn clean install -pl schemas

# Build query-api only
cd query-api && mvn clean package && cd ..
```

### Run Locally (without Kubernetes)
Not recommended for Phase 1 - requires manual Kafka, Apicurio, and PostgreSQL setup.

## 📚 Next Steps (Future Phases)

**Phase 1 Complete Features:**
- ✅ Multi-instance streams-processor support (StatefulSet + headless service)
- ✅ StreamsMetadata endpoints for instance discovery
- ✅ Parallel query aggregation across instances
- ✅ DNS-based instance discovery with health checks

**Upcoming Phases:**
- **Phase 2:** Add vehicle telemetry, warehouse operations, and order status topics
- **Phase 3:** Implement all 6 state stores with windowed aggregations
- **Phase 4:** Complete Query API with multi-source hybrid queries
- **Phase 5:** Production hardening, native image builds, comprehensive testing
- **Phase 6:** Demo optimization with sample LLM query scripts

## 🤝 Contributing

This is a demonstration project. See `design/implementation-plan.md` for complete architecture and implementation details.

## 📄 License

[Your License Here]

## 🙏 Acknowledgments

- Strimzi Kafka Operator for Kubernetes-native Kafka
- Apicurio Registry for schema management
- Quarkus for cloud-native Java framework
- Red Hat for event streaming expertise

---

**Phase 1 Status:** ✅ Complete - Ready for deployment and validation
