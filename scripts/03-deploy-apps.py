#!/usr/bin/env python3
"""Deploy SmartShip applications to Kubernetes."""
import argparse
import sys
from common import (
    kubectl,
    wait_for_condition,
    wait_for_statefulset_ready,
    verify_kafka_data_flow,
    NAMESPACE
)


def parse_args() -> argparse.Namespace:
    """Parse command-line arguments."""
    parser = argparse.ArgumentParser(
        description='Deploy SmartShip applications to Kubernetes (default: minikube)'
    )
    parser.add_argument(
        '--ocp', action='store_true',
        help='Deploy to OpenShift cluster'
    )
    return parser.parse_args()


def deploy_applications(target: str) -> None:
    """Deploy applications using base manifests or OpenShift overlay."""
    print(f"\n=== Deploying applications to {target} ===")

    if target == 'minikube':
        print("Using base application manifests")
        kubectl('apply', '-k', 'kubernetes/applications')

    elif target == 'openshift':
        overlay_path = 'kubernetes/overlays/openshift'
        print("Using OpenShift overlay with internal registry")
        print("Registry: image-registry.openshift-image-registry.svc:5000/smartship/*")
        kubectl('apply', '-k', overlay_path)

    print(f"✓ Applications deployed to {target}")


def main():
    args = parse_args()

    # Determine target based on flags
    target = 'openshift' if args.ocp else 'minikube'

    print("=" * 60)
    print(f"SmartShip Logistics - Deploy Applications ({target})")
    print("=" * 60)

    # Deploy applications using Kustomize overlays
    deploy_applications(target)

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
    print(f"Applications deployed successfully to {target}!")
    print("=" * 60)

    return 0


if __name__ == '__main__':
    sys.exit(main())
