#!/usr/bin/env python3
"""Deploy SmartShip applications to Kubernetes."""
import argparse
import sys
from common import (
    kubectl,
    wait_for_condition,
    wait_for_statefulset_ready,
    verify_kafka_data_flow,
    NAMESPACE,
    CLUSTER_TYPE
)


def parse_args() -> argparse.Namespace:
    """Parse command-line arguments."""
    parser = argparse.ArgumentParser(
        description='Deploy SmartShip applications to Kubernetes (auto-detects cluster from CLUSTER_TYPE)'
    )
    return parser.parse_args()


def deploy_applications(cluster_type: str) -> None:
    """Deploy applications using cluster-specific overlay.

    Args:
        cluster_type: Cluster type (kind, minikube, openshift)
    """
    print(f"\n=== Deploying applications to {cluster_type} ===")

    if cluster_type == 'kind':
        overlay_path = 'kubernetes/overlays/kind'
        print("Using kind overlay (imagePullPolicy: IfNotPresent)")
        kubectl('apply', '-k', overlay_path)

    elif cluster_type == 'minikube':
        print("Using base application manifests")
        kubectl('apply', '-k', 'kubernetes/applications')

    elif cluster_type == 'openshift':
        overlay_path = 'kubernetes/overlays/openshift'
        print("Using OpenShift overlay with internal registry")
        print("Registry: image-registry.openshift-image-registry.svc:5000/smartship/*")
        kubectl('apply', '-k', overlay_path)

    else:
        print(f"Warning: Unknown cluster type '{cluster_type}', using base manifests")
        kubectl('apply', '-k', 'kubernetes/applications')

    print(f"✓ Applications deployed to {cluster_type}")


def main():
    args = parse_args()

    print("=" * 60)
    print(f"SmartShip Logistics - Deploy Applications ({CLUSTER_TYPE})")
    print("=" * 60)

    # Deploy applications using Kustomize overlays
    deploy_applications(CLUSTER_TYPE)

    print("\n=== Waiting for Data Generators ===")
    wait_for_condition('deployment', 'data-generators', 'Available', timeout=300)

    print("\n=== Verifying Kafka Data Flow (all topics) ===")
    verify_kafka_data_flow('shipment.events', max_messages=5, timeout=60)
    verify_kafka_data_flow('vehicle.telemetry', max_messages=5, timeout=60)
    verify_kafka_data_flow('warehouse.operations', max_messages=5, timeout=60)
    verify_kafka_data_flow('order.status', max_messages=5, timeout=60)

    print("\n=== Waiting for Streams Processor ===")
    wait_for_statefulset_ready('streams-processor', replicas=1, timeout=300)

    print("\n=== Waiting for Query API ===")
    wait_for_condition('deployment', 'query-api', 'Available', timeout=300)

    print("\n=== Deployment complete! ===")
    kubectl('get', 'pods', '-n', NAMESPACE)

    # Show services including the new headless service
    print("\n=== Services ===")
    kubectl('get', 'svc', '-n', NAMESPACE)

    print("\n" + "=" * 60)
    print(f"Applications deployed successfully to {CLUSTER_TYPE}!")
    print("=" * 60)

    return 0


if __name__ == '__main__':
    sys.exit(main())
