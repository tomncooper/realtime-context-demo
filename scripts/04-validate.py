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
    result = run_command(
        ['curl', '-s', 'http://localhost:7070/state/active-shipments-by-status/IN_TRANSIT'],
        capture_output=True
    )
    print(result.stdout)

def test_query_api():
    """Test Query API endpoints."""
    for status in ['CREATED', 'IN_TRANSIT', 'DELIVERED']:
        print(f"\nQuerying status: {status}")
        result = run_command(
            ['curl', '-s', f'http://localhost:8080/api/shipments/by-status/{status}'],
            capture_output=True
        )
        print(result.stdout)

def main():
    print("=" * 60)
    print("SmartShip Logistics - Validation")
    print("=" * 60)

    print("\n=== Validating Infrastructure ===")
    result = kubectl('get', 'kafka', KAFKA_CLUSTER_NAME, '-n', NAMESPACE,
                    '-o', 'jsonpath={.status.conditions[?(@.type=="Ready")].status}')
    if result.stdout:
        print(f"Kafka Ready Status: {result.stdout.strip()}")

    print("\n=== Validating PostgreSQL ===")
    kubectl('exec', '-it', 'statefulset/postgresql', '-n', NAMESPACE, '--',
           'psql', '-U', 'smartship', '-d', 'smartship',
           '-c', 'SELECT COUNT(*) FROM warehouses;')

    print("\n=== Checking event generation ===")
    kubectl('logs', 'deployment/data-generators', '-n', NAMESPACE, '--tail=20')

    print("\n=== Verifying Kafka Data Flow ===")
    verify_kafka_data_flow('shipment.events', max_messages=5, timeout=60)

    print("\n=== Querying state store ===")
    port_forward_and_test('streams-processor', 7070, test_state_store)

    print("\n=== Testing Query API ===")
    port_forward_and_test('query-api', 8080, test_query_api)

    print("\n" + "=" * 60)
    print("Validation complete!")
    print("=" * 60)

    return 0

if __name__ == '__main__':
    sys.exit(main())
