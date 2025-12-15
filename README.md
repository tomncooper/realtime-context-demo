# SmartShip Logistics - Real-Time Context Demo

A real-time event streaming demonstration for a regional logistics and fulfillment company, showcasing Kafka Streams, materialized views, and an LLM-queryable API.

## Current Status: Phase 3 Complete

**Status:** ✅ Phase 1 | ✅ Phase 2 | ✅ Phase 3 (6 state stores, 14 API endpoints)
**Goal:** Full real-time analytics with 6 materialized views, windowed aggregations, and comprehensive REST API

## 🏗️ Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│ Shipment Events │     │ Vehicle         │     │ Warehouse       │
│ Generator       │     │ Telemetry Gen   │     │ Operations Gen  │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                       │
         ▼                       ▼                       ▼
    ┌─────────────────────────────────────────────────────────┐
    │              Kafka (KRaft) - 4 Topics                   │
    │  shipment.events | vehicle.telemetry | warehouse.ops    │
    └───────────────────────────┬─────────────────────────────┘
                                │
                                ▼
              ┌─────────────────────────────────────┐
              │     Kafka Streams Processor         │
              │         (StatefulSet)               │
              │                                     │
              │  6 State Stores:                    │
              │  • active-shipments-by-status       │
              │  • vehicle-current-state            │
              │  • shipments-by-customer            │
              │  • late-shipments                   │
              │  • warehouse-realtime-metrics (15m) │
              │  • hourly-delivery-performance (1h) │
              │                                     │
              │  Pods: 0, 1, 2... (scalable)        │
              └──────────────────┬──────────────────┘
                                 │
                                 ▼
              ┌─────────────────────────────────────┐
              │          Query API (Quarkus)        │
              │                                     │
              │  14 REST Endpoints:                 │
              │  • Shipments (status, late)         │
              │  • Vehicles (state, location)       │
              │  • Customers (shipment stats)       │
              │  • Warehouses (real-time metrics)   │
              │  • Performance (hourly delivery)    │
              │                                     │
              │  Multi-instance discovery           │
              │  Parallel query aggregation         │
              └─────────────────────────────────────┘
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
- `data-generators-...` - Event producers (4 generators)
- `streams-processor-0` - Kafka Streams app with 6 state stores (StatefulSet)
- `query-api-...` - REST API with 14 endpoints

```bash
# Check StatefulSet status
kubectl get statefulset -n smartship
```

### Monitor Event Generation
```bash
kubectl logs -f deployment/data-generators -n smartship
```

### Query State Stores (Interactive Queries - All 6 stores)
```bash
kubectl port-forward svc/streams-processor 7070:7070 -n smartship &

# State Store 1: Shipment counts by status
curl http://localhost:7070/state/active-shipments-by-status | jq

# State Store 2: Vehicle current state
curl http://localhost:7070/state/vehicle-current-state | jq

# State Store 3: Customer shipment stats
curl http://localhost:7070/state/shipments-by-customer | jq

# State Store 4: Late shipments
curl http://localhost:7070/state/late-shipments | jq

# State Store 5: Warehouse metrics (15-min window)
curl http://localhost:7070/state/warehouse-realtime-metrics | jq

# State Store 6: Hourly delivery performance (1-hour window)
curl http://localhost:7070/state/hourly-delivery-performance | jq

# Query StreamsMetadata (multi-instance support)
curl http://localhost:7070/metadata/instances/active-shipments-by-status | jq
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

### Query via REST API (14 endpoints)
```bash
kubectl port-forward svc/query-api 8080:8080 -n smartship &

# Shipment endpoints
curl http://localhost:8080/api/shipments/status/all | jq
curl http://localhost:8080/api/shipments/by-status/IN_TRANSIT | jq
curl http://localhost:8080/api/shipments/late | jq

# Vehicle endpoints
curl http://localhost:8080/api/vehicles/state | jq
curl http://localhost:8080/api/vehicles/state/VH-001 | jq

# Customer endpoints
curl http://localhost:8080/api/customers/shipments/all | jq
curl http://localhost:8080/api/customers/CUST-001/shipments | jq

# Warehouse metrics (15-min windows)
curl http://localhost:8080/api/warehouses/metrics/all | jq
curl http://localhost:8080/api/warehouses/WH-RTM/metrics | jq

# Hourly delivery performance
curl http://localhost:8080/api/performance/hourly | jq
curl http://localhost:8080/api/performance/hourly/WH-RTM | jq

# Health check
curl http://localhost:8080/api/health | jq

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

### Event Flow (Phase 3)
1. **Data Generators** produce events to 4 Kafka topics
   - **Shipment Events** (50-80/sec): Full 9-state lifecycle with 5% exception rate
   - **Vehicle Telemetry** (20-30/sec): Position updates for 50 vehicles
   - **Warehouse Operations** (15-25/sec): 7 operation types with 3% error rate
   - **Order Status** (10-15/sec): 4 SLA tiers

2. **Kafka Streams Processor** (StatefulSet) maintains 6 state stores
   - **active-shipments-by-status**: Count of shipments per status
   - **vehicle-current-state**: Latest telemetry per vehicle
   - **shipments-by-customer**: Aggregated stats per customer
   - **late-shipments**: Shipments past expected delivery (30-min grace)
   - **warehouse-realtime-metrics**: 15-minute tumbling window
   - **hourly-delivery-performance**: 1-hour hopping window (30-min advance)

3. **Query API** provides 14 REST endpoints
   - Shipments: status counts, late shipments
   - Vehicles: current state, location
   - Customers: shipment statistics
   - Warehouses: real-time operation metrics
   - Performance: hourly delivery stats
   - Multi-instance query support with parallel aggregation

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
├── data-generators/                 # Event producers
│   └── src/main/java/.../ShipmentEventGenerator.java
├── streams-processor/               # Kafka Streams (StatefulSet) - 6 state stores
│   └── src/main/java/com/smartship/streams/
│       ├── LogisticsTopology.java          # 6 state store definitions
│       ├── StreamsApplication.java
│       ├── InteractiveQueryServer.java     # 12 query endpoints
│       ├── StreamsMetadataResponse.java
│       ├── model/                          # State store value types
│       │   ├── VehicleState.java
│       │   ├── CustomerShipmentStats.java
│       │   ├── LateShipmentDetails.java
│       │   ├── DeliveryStats.java
│       │   └── WarehouseMetrics.java
│       └── serde/JsonSerde.java            # Custom JSON serialization
├── query-api/                       # Quarkus REST API - 14 endpoints
│   └── src/main/java/com/smartship/api/
│       ├── QueryResource.java              # REST endpoints
│       ├── KafkaStreamsQueryService.java   # Distributed query support
│       ├── model/                          # Response DTOs
│       └── services/StreamsInstanceDiscoveryService.java
├── kubernetes/                      # K8s manifests
│   ├── infrastructure/              # Core infrastructure (Kafka, PostgreSQL, etc.)
│   │   └── init.sql                 # PostgreSQL schema (used by configMapGenerator)
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

### Kafka Topics (4 topics)
| Topic | Events/sec | Key Fields |
|-------|------------|------------|
| `shipment.events` | 50-80 | shipment_id, customer_id, warehouse_id, event_type |
| `vehicle.telemetry` | 20-30 | vehicle_id, location, status, current_load |
| `warehouse.operations` | 15-25 | event_id, warehouse_id, operation_type |
| `order.status` | 10-15 | order_id, customer_id, shipment_ids, priority |

### State Stores (6 stores)
| Store | Type | Key | Value |
|-------|------|-----|-------|
| `active-shipments-by-status` | KeyValue | ShipmentEventType | Count |
| `vehicle-current-state` | KeyValue | vehicle_id | VehicleState |
| `shipments-by-customer` | KeyValue | customer_id | CustomerShipmentStats |
| `late-shipments` | KeyValue | shipment_id | LateShipmentDetails |
| `warehouse-realtime-metrics` | Windowed (15m) | warehouse_id | WarehouseMetrics |
| `hourly-delivery-performance` | Windowed (1h) | warehouse_id | DeliveryStats |

### PostgreSQL Reference Data (6 tables)
| Table | Records | Description |
|-------|---------|-------------|
| warehouses | 5 | Rotterdam, Frankfurt, Barcelona, Warsaw, Stockholm |
| customers | 200 | Companies with SLA tiers |
| vehicles | 50 | Vans, box trucks, semi-trailers |
| products | 10,000 | SKUs across 5 categories |
| drivers | 75 | With license types and assignments |
| routes | 100 | Predefined routes with distance/time |

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

## 📚 Phase Summary

**Phase 1 (Complete):** Minimal end-to-end with 1 topic, 1 state store
**Phase 2 (Complete):** All 4 topics producing events, 6 PostgreSQL tables
**Phase 3 (Complete):** All 6 state stores operational with full Query API

**Phase 3 Features:**
- ✅ 6 state stores consuming 3 Kafka topics
- ✅ 4 KeyValue stores + 2 Windowed stores
- ✅ 14 REST API endpoints across 5 resource groups
- ✅ JsonSerde for custom state store value serialization
- ✅ Multi-instance query support with parallel aggregation

**Upcoming Phases:**
- **Phase 4:** Complete Query API with PostgreSQL hybrid queries, order.status consumption
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

**Phase 3 Status:** ✅ Complete - All 6 state stores operational with 14 API endpoints
