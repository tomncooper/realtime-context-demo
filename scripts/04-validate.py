#!/usr/bin/env python3
"""Validate SmartShip deployment."""
import sys
import time
import subprocess

from common import (
    kubectl,
    run_command,
    verify_kafka_data_flow,
    NAMESPACE,
    KAFKA_CLUSTER_NAME
)


def port_forward_and_test(service: str, port: int, test_func):
    """Start port-forward and run test function."""
    # Start port-forward in background
    proc = subprocess.Popen(
        ['kubectl', 'port-forward', f'svc/{service}', f'{port}:{port}', '-n', 'smartship'],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    )

    try:
        time.sleep(3)  # Wait for port-forward to establish
        test_func()
    finally:
        proc.terminate()
        proc.wait()


def test_state_store():
    """Test Kafka Streams state store."""
    run_command(
        ['curl', '-s', 'http://localhost:7070/state/active-shipments-by-status/IN_TRANSIT'],
        capture_output=True
    )


def test_metadata_endpoints():
    """Test Kafka Streams metadata endpoints (for multi-instance support)."""
    print("\n--- Testing metadata endpoints ---")

    # Test /metadata/instances endpoint
    print("\nQuerying all instances for state store:")
    run_command(
        ['curl', '-s', 'http://localhost:7070/metadata/instances/active-shipments-by-status'],
        capture_output=True
    )

    # Test /metadata/instance-for-key endpoint
    print("\nQuerying instance for key 'IN_TRANSIT':")
    run_command(
        ['curl', '-s', 'http://localhost:7070/metadata/instance-for-key/active-shipments-by-status/IN_TRANSIT'],
        capture_output=True
    )


def test_query_api():
    """Test Query API endpoints."""
    for status in ['CREATED', 'IN_TRANSIT', 'DELIVERED']:
        print(f"\nQuerying status: {status}")
        run_command(
            ['curl', '-s', f'http://localhost:8080/api/shipments/by-status/{status}'],
            capture_output=True
        )

    # Test aggregated query (all statuses)
    print("\nQuerying all statuses (aggregated):")
    run_command(
        ['curl', '-s', 'http://localhost:8080/api/shipments/status/all'],
        capture_output=True
    )


def validate_statefulset():
    """Validate StatefulSet configuration for streams-processor."""
    print("\n=== Validating Streams Processor StatefulSet ===")

    # Check StatefulSet status
    result = run_command([
        'kubectl', 'get', 'statefulset', 'streams-processor', '-n', NAMESPACE,
        '-o', 'jsonpath={.status.readyReplicas}/{.spec.replicas}'
    ], capture_output=True)
    print(f"StatefulSet replicas ready: {result.stdout.strip()}")

    # Check headless service exists
    print("\nChecking headless service:")
    result = run_command([
        'kubectl', 'get', 'svc', 'streams-processor-headless', '-n', NAMESPACE,
        '-o', 'jsonpath={.spec.clusterIP}'
    ], capture_output=True, check=False)
    if result.returncode == 0:
        cluster_ip = result.stdout.strip()
        if cluster_ip == 'None':
            print("✓ Headless service configured correctly (clusterIP: None)")
        else:
            print(f"✗ Headless service has unexpected clusterIP: {cluster_ip}")
    else:
        print("✗ Headless service not found")

    # Check APPLICATION_SERVER environment variable
    print("\nChecking APPLICATION_SERVER env var in pod:")
    result = run_command([
        'kubectl', 'exec', 'streams-processor-0', '-n', NAMESPACE, '--',
        'printenv', 'APPLICATION_SERVER'
    ], capture_output=True, check=False)
    if result.returncode == 0:
        print(f"✓ APPLICATION_SERVER: {result.stdout.strip()}")
    else:
        print("✗ APPLICATION_SERVER not set")


def main():
    print("=" * 60)
    print("SmartShip Logistics - Validation")
    print("=" * 60)

    print("\n=== Validating Infrastructure ===")
    result = kubectl('get', 'kafka', KAFKA_CLUSTER_NAME, '-n', NAMESPACE,
                    '-o', 'jsonpath={.status.conditions[?(@.type=="Ready")].status}')
    if result.stdout:
        print(f"Kafka Ready Status: {result.stdout.strip()}")

    print("\n=== Validating PostgreSQL (Phase 2 tables) ===")
    kubectl('exec', '-it', 'statefulset/postgresql', '-n', NAMESPACE, '--',
           'psql', '-U', 'smartship', '-d', 'smartship',
           '-c', "SELECT 'warehouses' as table_name, COUNT(*) as cnt FROM warehouses UNION ALL SELECT 'customers', COUNT(*) FROM customers UNION ALL SELECT 'vehicles', COUNT(*) FROM vehicles UNION ALL SELECT 'products', COUNT(*) FROM products UNION ALL SELECT 'drivers', COUNT(*) FROM drivers UNION ALL SELECT 'routes', COUNT(*) FROM routes;")

    print("\n=== Checking event generation ===")
    kubectl('logs', 'deployment/data-generators', '-n', NAMESPACE, '--tail=20')

    print("\n=== Verifying Kafka Data Flow (all topics) ===")
    verify_kafka_data_flow('shipment.events', max_messages=5, timeout=60)
    verify_kafka_data_flow('vehicle.telemetry', max_messages=5, timeout=60)
    verify_kafka_data_flow('warehouse.operations', max_messages=5, timeout=60)
    verify_kafka_data_flow('order.status', max_messages=5, timeout=60)

    # Validate StatefulSet configuration
    validate_statefulset()

    print("\n=== Querying state store ===")
    port_forward_and_test('streams-processor', 7070, test_state_store)

    print("\n=== Testing metadata endpoints (multi-instance support) ===")
    port_forward_and_test('streams-processor', 7070, test_metadata_endpoints)

    print("\n=== Testing Query API ===")
    port_forward_and_test('query-api', 8080, test_query_api)

    print("\n" + "=" * 60)
    print("Validation complete!")
    print("=" * 60)

    return 0


if __name__ == '__main__':
    sys.exit(main())
