# SmartShip Logistics - Real-Time Context Demo

A real-time event streaming demonstration showcasing Kafka Streams, materialized views, and an LLM-queryable API for a regional logistics company.

For detailed architecture, implementation details, and query examples see the [documentation](https://tomncooper.github.io/realtime-context-demo/).

## Prerequisites

### Required Tools

#### For Minikube
- Java 25 LTS (or compatible JDK)
- Maven 3.9+
- Python 3.9+
- Podman or Docker CLI
- Minikube
- kubectl
- ollama (if using LLM chatbot functionality)

### Start Minikube
```bash
# Standard deployment (without LLM chatbot)
minikube start --cpus=4 --memory=12288 --disk-size=50g

# With LLM chatbot (Ollama)
minikube start --cpus=6 --memory=16384 --disk-size=80g

# On macos with podman/docker
minikube start --cpus=6 --memory=16384 --disk-size=80g --driver=podman
```

## Quick Start (Minikube)

### 1. Setup Infrastructure

You can run the base setup which includes downloading and installing Ollama into minikube:
```bash
python3 scripts/01-setup-infra.py
```

Alternatively, you can specify a model to download to your local Ollama installation and load into minikube.
This means the next time you run the setup will be faster as the model is cached in your local Ollama installation and not downloaded again:
```bash
python3 scripts/01-setup-infra.py --models llama3.2
```

You can also skip the Ollama installation if you do not want the LLM chatbot functionality:
```bash
python3 scripts/01-setup-infra.py --skip-ollama
```

This script will deploy a Strimzi-managed Kafka cluster, Apicurio Registry, PostgreSQL, and optionally Ollama.

### 2. Build All Modules

```bash
# Optional: Set container runtime (default: podman)
export CONTAINER_RUNTIME=podman  # or docker

# Export target image platform
export TARGET_ARCH=amd64 # or arm64

# Build and load images to minikube
python3 scripts/02-build-all.py --load-minikube

# OR build native images (faster startup, lower memory)
python3 scripts/02-build-all.py --native --load-minikube
```

### 3. Deploy Applications

```bash
python3 scripts/03-deploy-apps.py
```

### 4. Validate Deployment

```bash
python3 scripts/04-validate.py
```

## OpenShift Deployment

### Prerequisites

1. **OpenShift Access**: Ensure you have access to an OpenShift cluster
2. **Login**: Log in to your OpenShift cluster
   ```bash
   oc login <your-openshift-api-url>
   ```

### 1. Setup Infrastructure

Deploy infrastructure components (Kafka, Apicurio Registry, PostgreSQL):
```bash
python3 scripts/01-setup-infra.py
```

### 2. Build and Push Images to OpenShift Registry

```bash
# Optional: Set container runtime (default: podman)
export CONTAINER_RUNTIME=podman

# Optional: Set target architecture (auto-detected if not set)
export TARGET_ARCH=amd64  # or arm64

# Build images and push to OpenShift internal registry
python3 scripts/02-build-all.py --push-ocp

# OR build native images (faster startup, lower memory)
python3 scripts/02-build-all.py --native --push-ocp
```

### 3. Deploy Applications to OpenShift

```bash
# Deploy with OpenShift overlay (includes routes and registry images)
python3 scripts/03-deploy-apps.py --ocp
```

This deploys applications with:
- **Image references**: OpenShift internal registry (`image-registry.openshift-image-registry.svc:5000/smartship/*`)
- **HTTPS routes**: Automatic TLS termination and external access
- **Pull policy**: Always pull from registry

### 4. Access Applications via Routes

Get the route URLs:
```bash
oc get routes -n smartship
```

Access the applications:
- **Web UI Dashboard**: `https://query-api-smartship.apps.<your-cluster-domain>/`
- **API Swagger**: `https://query-api-smartship.apps.<your-cluster-domain>/swagger-ui`
- **Streams Processor**: `https://streams-processor-smartship.apps.<your-cluster-domain>/state/active-shipments-by-status`

### 5. Validate Deployment

```bash
python3 scripts/04-validate.py
```

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
