# SmartShip Logistics - Real-Time Context Demo

A real-time event streaming demonstration showcasing Kafka Streams, materialized views, and an LLM-queryable API for a regional logistics company.

For detailed architecture, implementation details, and query examples see the [documentation](https://tomncooper.github.io/realtime-context-demo/).

## Prerequisites

### Required Tools

- Java 25 LTS (or compatible JDK)
- Maven 3.9+
- Python 3.9+
- Podman or Docker CLI
- Minikube
- kubectl

### Start Minikube
```bash
# Standard deployment (without LLM chatbot)
minikube start --cpus=4 --memory=12288 --disk-size=50g

# With LLM chatbot (Ollama)
minikube start --cpus=6 --memory=16384 --disk-size=80g

# On macos with podman/docker
minikube start --cpus=6 --memory=16384 --disk-size=80g --driver=podman
```

## Quick Start

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

# Build and load images to minikube
python3 scripts/02-build-all.py

# OR build native images (faster startup, lower memory)
python3 scripts/02-build-all.py --native
```

### 3. Deploy Applications

```bash
python3 scripts/03-deploy-apps.py
```

### 4. Validate Deployment

```bash
python3 scripts/04-validate.py
```

## Testing the System

### Port Forwarding

```bash
kubectl port-forward svc/query-api 8080:8080 -n smartship &
kubectl port-forward svc/streams-processor 7070:7070 -n smartship &
```

### Web UI Dashboard

Open http://localhost:8080/ to access the dashboard with:

### Query Examples

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
