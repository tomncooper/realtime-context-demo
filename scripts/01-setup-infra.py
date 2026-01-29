#!/usr/bin/env python3
"""Setup Kubernetes infrastructure for SmartShip demo."""
import argparse
import sys
from common import (
    kubectl, wait_for_condition, wait_for_statefulset_ready,
    KAFKA_CLUSTER_NAME, CLUSTER_TYPE, ensure_ollama_models,
    upload_ollama_models_to_minikube,
    prepull_ollama_image, get_cluster_name
)


def parse_args():
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description='Setup Kubernetes infrastructure for SmartShip demo.',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Default: deploy with standard Ollama (downloads model in-cluster)
  python3 scripts/01-setup-infra.py

  # Pre-load models from local machine (downloads locally if needed)
  python3 scripts/01-setup-infra.py --models llama3.2

  # Pre-load multiple models
  python3 scripts/01-setup-infra.py --models llama3.2,mistral

  # Skip Ollama deployment entirely
  python3 scripts/01-setup-infra.py --skip-ollama
"""
    )
    parser.add_argument(
        '--models',
        type=str,
        help='Comma-separated list of Ollama models to pre-load (e.g., llama3.2,mistral). '
             'Models will be downloaded locally if not present, then uploaded to minikube.'
    )
    parser.add_argument(
        '--skip-ollama',
        action='store_true',
        help='Skip Ollama deployment entirely (for testing without LLM)'
    )
    return parser.parse_args()


def deploy_ollama_with_models(models: list):
    """Pre-load models and deploy Ollama with hostPath volume."""
    print("\n=== Pre-loading Ollama Models ===")

    # Ensure models are available locally (download if needed)
    if not ensure_ollama_models(models):
        print("ERROR: Failed to ensure Ollama models are available")
        return False

    # Upload models to cluster node (minikube)
    cluster_name = get_cluster_name()
    if CLUSTER_TYPE == 'minikube':
        if not upload_ollama_models_to_minikube():
            print("ERROR: Failed to upload models to minikube")
            return False

    # Pre-pull the Ollama container image to avoid long download during pod startup
    prepull_ollama_image()

    # Deploy Ollama with hostPath variant
    print("\n=== Deploying Ollama (hostPath mode) ===")
    kubectl('apply', '-f', 'kubernetes/infrastructure/ollama-hostpath.yaml')

    # Wait for Ollama to be ready
    print(
        "Waiting for Ollama StatefulSet to be ready, " +
        "depending on the size of the model this may take some time..."
    )
    wait_for_statefulset_ready('ollama', replicas=1, timeout=300)

    return True


def deploy_ollama_standard():
    """Deploy Ollama with standard PVC (downloads model in-cluster)."""
    # Pre-pull the Ollama container image to avoid long download during pod startup
    prepull_ollama_image()

    print("\n=== Deploying Ollama (PVC mode - will download model in-cluster) ===")
    kubectl('apply', '-f', 'kubernetes/infrastructure/ollama.yaml')

    # Wait for Ollama to be ready (may take a while due to model download)
    print("Waiting for Ollama StatefulSet to be ready (model download may take several minutes)...")
    wait_for_statefulset_ready('ollama', replicas=1, timeout=600)


def main():
    args = parse_args()

    print("=" * 60)
    print("SmartShip Logistics - Infrastructure Setup")
    print("=" * 60)

    if args.models:
        print(f"Mode: Pre-load models from local machine")
        print(f"Models: {args.models}")
    elif args.skip_ollama:
        print("Mode: Skip Ollama deployment")
    else:
        print("Mode: Standard (download model in-cluster)")

    print("\n=== Installing Strimzi Operator ===")

    # Install Strimzi operator
    kubectl('apply', '-k', 'kubernetes/strimzi/operator')

    # Wait for operator
    wait_for_condition(
        'deployment',
        'strimzi-cluster-operator',
        'Available',
        namespace='strimzi',
        timeout=300
    )

    print("\n=== Deploying Infrastructure ===")

    # Deploy base infrastructure (Kafka, PostgreSQL, Apicurio - not Ollama)
    kubectl('apply', '-k', 'kubernetes/infrastructure')

    print("\n=== Waiting for Kafka cluster (KRaft mode) ===")
    wait_for_condition('kafka', KAFKA_CLUSTER_NAME, 'Ready', timeout=600)

    print("\n=== Waiting for Kafka Topic ===")
    wait_for_condition('kafkatopic', 'shipment.events', 'Ready', timeout=600)

    # Handle Ollama deployment based on arguments
    if args.skip_ollama:
        print("\n=== Skipping Ollama deployment (--skip-ollama specified) ===")
    elif args.models:
        models = [m.strip() for m in args.models.split(',')]
        if not deploy_ollama_with_models(models):
            print("ERROR: Ollama deployment with pre-loaded models failed")
            return 1
    else:
        deploy_ollama_standard()

    print("\n=== Infrastructure ready! ===")

    print("\n" + "=" * 60)
    print("Infrastructure setup complete!")
    if args.models:
        print(f"Pre-loaded models: {args.models}")
    elif not args.skip_ollama:
        print("Ollama is downloading the model in the init container.")
        print("Check progress: kubectl logs ollama-0 -n smartship -c model-loader")
    print("=" * 60)

    return 0


if __name__ == '__main__':
    sys.exit(main())
