#!/usr/bin/env python3
"""Setup Kubernetes infrastructure for SmartShip demo."""
import sys
from common import kubectl, wait_for_condition, KAFKA_CLUSTER_NAME


def main():
    print("=" * 60)
    print("SmartShip Logistics - Infrastructure Setup")
    print("=" * 60)

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

    # Deploy infrastructure components
    kubectl('apply', '-k', 'kubernetes/infrastructure')

    print("\n=== Waiting for Kafka cluster (KRaft mode) ===")
    wait_for_condition('kafka', KAFKA_CLUSTER_NAME, 'Ready', timeout=600)

    print("\n=== Waiting for Kafka Topic ===")
    wait_for_condition('kafkatopic', 'shipment.events', 'Ready', timeout=600)

    print("\n=== Infrastructure ready! ===")

    print("\n" + "=" * 60)
    print("Infrastructure setup complete!")
    print("=" * 60)

    return 0


if __name__ == '__main__':
    sys.exit(main())
