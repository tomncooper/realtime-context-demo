# SmartShip Logistics - Real-Time Context Demo

A real-time event streaming demonstration for a regional logistics and fulfillment company, showcasing Kafka Streams, materialized views, and an LLM-queryable API.

For a detailed description of this project see the [walk-through](docs/index.md).

## 🏗️ Architecture

```
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│ Shipment Events │  │ Vehicle         │  │ Warehouse       │  │ Order Status    │
│ Generator       │  │ Telemetry Gen   │  │ Operations Gen  │  │ Generator       │
└────────┬────────┘  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘
         │                    │                    │                    │
         ▼                    ▼                    ▼                    ▼
    ┌──────────────────────────────────────────────────────────────────────────┐
    │                      Kafka (KRaft) - 4 Topics                            │
    │  shipment.events | vehicle.telemetry | warehouse.ops | order.status      │
    └────────────────────────────────────┬─────────────────────────────────────┘
                                         │
                                         ▼
              ┌─────────────────────────────────────┐
              │     Kafka Streams Processor         │
              │         (StatefulSet)               │
              │                                     │
              │  9 State Stores (Phase 4):          │
              │  • active-shipments-by-status       │
              │  • vehicle-current-state            │
              │  • shipments-by-customer            │
              │  • late-shipments                   │
              │  • warehouse-realtime-metrics (15m) │
              │  • hourly-delivery-performance (1h) │
              │  • order-current-state (Phase 4)    │
              │  • orders-by-customer (Phase 4)     │
              │  • order-sla-tracking (Phase 4)     │
              │                                     │
              │  Pods: 0, 1, 2... (scalable)        │
              └──────────────────┬──────────────────┘
                                 │
         ┌───────────────────────┼───────────────────────┐
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────┐   ┌─────────────────────────┐   ┌─────────────────┐
│   PostgreSQL    │   │    Query API (Quarkus)  │   │   OpenAPI/      │
│   (Reference)   │   │                         │   │   Swagger UI    │
│                 │◀──│  44+ REST Endpoints:    │──▶│                 │
│  6 Tables:      │   │  • Kafka Streams (14)   │   │  /swagger-ui    │
│  • warehouses   │   │  • Reference Data (17)  │   │                 │
│  • customers    │   │  • Hybrid Queries (7)   │   └─────────────────┘
│  • vehicles     │   │  • Order State (6)      │
│  • products     │   │                         │
│  • drivers      │   │  Multi-source queries   │
│  • routes       │   │  with HybridQueryResult │
└─────────────────┘   └─────────────────────────┘
```

## 📋 Prerequisites

### Required Tools
- Java 25 LTS (or compatible JDK)
- Maven 3.9+
- Python 3.9+
- Podman or Docker CLI
- Minikube
- kubectl

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
- Create Kafka topics

### 2. Build All Modules
```bash
# Optional: Set container runtime (default: podman)
export CONTAINER_RUNTIME=podman  # or docker

# Build JVM mode (default, faster build)
python3 scripts/02-build-all.py

# OR build native image (slower build, faster runtime)
python3 scripts/02-build-all.py --native --native-container-image
```

This will:
- Build Java modules (schemas, common, data-generators, streams-processor)
- Build Quarkus query-api (JVM or native mode)
- Create container images
- Load images into minikube

**Native Image Benefits (Phase 5):**
- Startup time: <100ms (vs ~10s JVM)
- Memory: 64-128Mi (vs 256-512Mi JVM)
- Container size: ~50MB (vs ~200MB JVM)

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

## 🔍 Testing the System

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

### Query via REST API
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

# Warehouse metrics (15-min windows)
curl http://localhost:8080/api/warehouses/metrics/all | jq
curl http://localhost:8080/api/warehouses/WH-RTM/metrics | jq

# Hourly delivery performance
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

# Hybrid query endpoints (Phase 4 - Kafka Streams + PostgreSQL)
curl http://localhost:8080/api/hybrid/customers/CUST-0001/overview | jq
curl http://localhost:8080/api/hybrid/vehicles/VEH-001/enriched | jq
curl http://localhost:8080/api/hybrid/warehouses/WH-RTM/status | jq

# Health check
curl http://localhost:8080/api/health | jq

# OpenAPI/Swagger UI
open http://localhost:8080/swagger-ui
```

### ID Formats 
- **Customers:** `CUST-0001` through `CUST-0200` (4 digits, zero-padded)
- **Vehicles:** `VEH-001` through `VEH-050` (3 digits)
- **Drivers:** `DRV-001` through `DRV-075` (3 digits)
- **Warehouses:** `WH-RTM`, `WH-FRA`, `WH-BCN`, `WH-WAW`, `WH-STO`

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

## 📖 Documentation

The project includes comprehensive documentation with architecture diagrams and implementation details.

### Online Documentation
The documentation is hosted on GitHub Pages (once deployed):
- **Website**: https://tomncooper.github.io/realtime-context-demo/
- **Source**: [docs/index.md](docs/index.md)

### Building Documentation Locally

The documentation can be built locally for preview or PDF generation.

**Requirements:**
- Python 3.9+
- pandoc
- weasyprint (`pip install weasyprint`)
- mermaid-cli (`npm install -g @mermaid-js/mermaid-cli`)

**Build Commands:**
```bash
# Build both HTML and PDF
python scripts/build-docs.py

# Build HTML only (faster, no mermaid-cli needed)
python scripts/build-docs.py --html-only

# Build PDF only
python scripts/build-docs.py --pdf-only
```

**Preview locally:**
```bash
python -m http.server -d docs/_site 8000
# Open http://localhost:8000
```

**Output:**
- `docs/_site/index.html` - HTML with client-side Mermaid.js diagrams
- `docs/_site/index.pdf` - PDF with pre-rendered PNG diagrams

### GitHub Pages Deployment
Documentation is automatically built and deployed via GitHub Actions when changes are pushed to the `main` branch. The workflow:
1. Renders Mermaid diagrams to PNG (for PDF)
2. Builds HTML with client-side Mermaid.js
3. Generates downloadable PDF
4. Deploys to GitHub Pages
