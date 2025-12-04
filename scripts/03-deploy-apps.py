#!/usr/bin/env python3
"""Deploy SmartShip applications to Kubernetes."""
import sys
from common import (
    kubectl,
    run_command,
    wait_for_condition,
    wait_for_statefulset_ready,
    verify_kafka_data_flow,
    NAMESPACE
)


def delete_old_deployment_if_exists(name: str) -> None:
    """Delete old Deployment if it exists (for migration to StatefulSet)."""
    print(f"Checking for existing Deployment {name}...")
    result = run_command([
        'kubectl', 'get', 'deployment', name, '-n', NAMESPACE
    ], check=False, capture_output=True)

    if result.returncode == 0:
        print(f"Found existing Deployment {name}, deleting it...")
        kubectl('delete', 'deployment', name, '-n', NAMESPACE, '--ignore-not-found=true')
        print(f"✓ Deleted old Deployment {name}")


def main():
    print("=" * 60)
    print("SmartShip Logistics - Deploy Applications")
    print("=" * 60)

    print("\n=== Deploying Data Generators ===")
    kubectl('apply', '-f', 'kubernetes/applications/data-generators.yaml')
    wait_for_condition('deployment', 'data-generators', 'Available', timeout=300)

    print("\n=== Verifying Kafka Data Flow ===")
    verify_kafka_data_flow('shipment.events', max_messages=5, timeout=60)

    print("\n=== Deploying Streams Processor (StatefulSet) ===")
    # Delete old Deployment if migrating to StatefulSet
    delete_old_deployment_if_exists('streams-processor')

    # Apply the StatefulSet and services
    kubectl('apply', '-f', 'kubernetes/applications/streams-processor.yaml')

    # Wait for StatefulSet pods to be ready (starts with 1 replica)
    wait_for_statefulset_ready('streams-processor', replicas=1, timeout=300)

    print("\n=== Deploying Query API ===")
    kubectl('apply', '-f', 'kubernetes/applications/query-api.yaml')
    wait_for_condition('deployment', 'query-api', 'Available', timeout=300)

    print("\n=== Deployment complete! ===")
    kubectl('get', 'pods', '-n', NAMESPACE)

    # Show services including the new headless service
    print("\n=== Services ===")
    kubectl('get', 'svc', '-n', NAMESPACE)

    print("\n" + "=" * 60)
    print("Applications deployed successfully!")
    print("=" * 60)

    return 0

if __name__ == '__main__':
    sys.exit(main())
