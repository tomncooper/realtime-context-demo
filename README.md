# SmartShip Logistics - Real-Time Context Demo

A real-time event streaming demonstration showcasing Kafka Streams, materialized views, and an LLM-queryable API for a regional logistics company.

For detailed architecture, implementation details, and query examples see the [documentation](https://tomncooper.github.io/realtime-context-demo/).

## Prerequisites

### Required Tools

- Java 25 LTS (or compatible JDK)
- Maven 3.9+
- Python 3.9+
- Podman or Docker CLI
- **kind** or **Minikube** (Kubernetes cluster)
- kubectl
- ollama (if using LLM chatbot functionality)

### Start Kubernetes Cluster

#### Option 1: kind

```bash
# Create with default settings
kind create cluster --name kind-cluster
```

#### Option 2: Minikube

```bash
# Standard deployment (without LLM chatbot)
minikube start --cpus=4 --memory=12288 --disk-size=50g

# With LLM chatbot (Ollama)
minikube start --cpus=6 --memory=16384 --disk-size=80g

# On macOS with podman/docker
minikube start --cpus=6 --memory=16384 --disk-size=80g --driver=podman
```

## Quick Start

**Step 0: Set your cluster type** (optional, default is `minikube`):
```bash
export CLUSTER_TYPE=kind        # recommended for new users
# OR
export CLUSTER_TYPE=minikube    # default
# OR
export CLUSTER_TYPE=openshift   # for OpenShift deployments
```

All subsequent commands work the same regardless of cluster type!

### 1. Setup Infrastructure

```bash
python3 scripts/01-setup-infra.py
```

**Options:**
```bash
# Pre-load Ollama models from local machine (faster subsequent deployments)
python3 scripts/01-setup-infra.py --models llama3.2

# Skip Ollama (no LLM chatbot)
python3 scripts/01-setup-infra.py --skip-ollama
```

This deploys: Strimzi-managed Kafka cluster, Apicurio Registry, PostgreSQL, and optionally Ollama.

### 2. Build All Modules

```bash
# Optional: Set container runtime (default: podman)
export CONTAINER_RUNTIME=podman  # or docker

# Optional: Set target architecture (default: amd64)
export TARGET_ARCH=amd64  # or arm64

# Build and load images to cluster (auto-detects based on CLUSTER_TYPE)
python3 scripts/02-build-all.py --load-images

# OR build native images (faster startup, lower memory)
python3 scripts/02-build-all.py --native --load-images
```

### 3. Deploy Applications

```bash
python3 scripts/03-deploy-apps.py
```

Automatically uses the correct overlay for your cluster type (kind/minikube/openshift).

### 4. Validate Deployment

```bash
python3 scripts/04-validate.py
```

## OpenShift-Specific Notes

For OpenShift deployments, follow the same Quick Start steps above with `CLUSTER_TYPE=openshift`, plus these additional prerequisites:

### Prerequisites

1. **Login to OpenShift**:
   ```bash
   oc login <your-openshift-api-url>
   ```

2. **Set cluster type**:
   ```bash
   export CLUSTER_TYPE=openshift
   ```

Then follow the standard Quick Start steps. The OpenShift overlay automatically configures:
- **Image references**: OpenShift internal registry (`image-registry.openshift-image-registry.svc:5000/smartship/*`)
- **HTTPS routes**: Automatic TLS termination and external access
- **Pull policy**: Always pull from registry

### Access Applications via Routes

Get the route URLs:
```bash
oc get routes -n smartship
```

Access the applications:
- **Web UI Dashboard**: `https://query-api-smartship.apps.<your-cluster-domain>/`
- **API Swagger**: `https://query-api-smartship.apps.<your-cluster-domain>/swagger-ui`
- **Streams Processor**: `https://streams-processor-smartship.apps.<your-cluster-domain>/state/active-shipments-by-status`

## Testing the System (Minikube)

### Port Forwarding

```bash
kubectl port-forward svc/query-api 8080:8080 -n smartship &
kubectl port-forward svc/streams-processor 7070:7070 -n smartship &
```

### Web UI Dashboard

Open http://localhost:8080/ to access the dashboard with:

### Query Examples (Minikube)

```bash
# REST API
curl http://localhost:8080/api/shipments/status/all | jq
curl http://localhost:8080/api/vehicles/state | jq
curl http://localhost:8080/api/reference/warehouses | jq

# LLM Chat
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "How many shipments are in transit?"}'

# OpenAPI/Swagger UI
open http://localhost:8080/swagger-ui
```

## Testing the System (OpenShift)

### Query Examples (OpenShift Routes)

Replace `<your-cluster-domain>` with your actual OpenShift cluster domain:

```bash
# Set your cluster domain for convenience
export CLUSTER_DOMAIN="apps.kornys.strimzi.app-services-dev.net"

# REST API
curl https://query-api-smartship.${CLUSTER_DOMAIN}/api/shipments/status/all | jq
curl https://query-api-smartship.${CLUSTER_DOMAIN}/api/vehicles/state | jq
curl https://query-api-smartship.${CLUSTER_DOMAIN}/api/reference/warehouses | jq

# LLM Chat
curl -X POST https://query-api-smartship.${CLUSTER_DOMAIN}/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "How many shipments are in transit?"}'

# Streams Processor Interactive Queries
curl https://streams-processor-smartship.${CLUSTER_DOMAIN}/state/active-shipments-by-status | jq
curl https://streams-processor-smartship.${CLUSTER_DOMAIN}/state/vehicle-current-state | jq

# OpenAPI/Swagger UI
open https://query-api-smartship.${CLUSTER_DOMAIN}/swagger-ui
```

### Monitor Logs

```bash
kubectl logs -f deployment/data-generators -n smartship
kubectl logs -f statefulset/streams-processor -n smartship
```

## Documentation

The project includes comprehensive documentation with architecture diagrams and implementation details.

- **Website**: https://tomncooper.github.io/realtime-context-demo/
- **Source**: [docs/index.md](docs/index.md)

### Building Documentation Locally

**Requirements:** Python 3.9+, pandoc, weasyprint, mermaid-cli
  
```bash
# Build HTML and PDF
python scripts/build-docs.py

# Build HTML only (faster)
python scripts/build-docs.py --html-only

# Preview
python -m http.server -d docs/_site 8000
```
