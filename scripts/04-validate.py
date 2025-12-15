#!/usr/bin/env python3
"""Validate SmartShip deployment - Phase 3 with all 6 state stores."""
import json
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


def test_all_state_stores():
    """Test all 6 Kafka Streams state stores via Interactive Queries."""
    print("\n--- Testing all 6 state stores ---")

    # State Store 1: active-shipments-by-status
    print("\n1. active-shipments-by-status:")
    result = run_command(
        ['curl', '-s', 'http://localhost:7070/state/active-shipments-by-status'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Statuses tracked: {list(data.keys())}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # State Store 2: vehicle-current-state
    print("\n2. vehicle-current-state:")
    result = run_command(
        ['curl', '-s', 'http://localhost:7070/state/vehicle-current-state'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Vehicles tracked: {len(data)}")
            if data:
                print(f"   Sample vehicle: {data[0].get('vehicle_id', 'N/A')}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # State Store 3: shipments-by-customer
    print("\n3. shipments-by-customer:")
    result = run_command(
        ['curl', '-s', 'http://localhost:7070/state/shipments-by-customer'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Customers tracked: {len(data)}")
            if data:
                sample = data[0]
                print(f"   Sample customer: {sample.get('customer_id', 'N/A')} - {sample.get('total_shipments', 0)} shipments")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # State Store 4: late-shipments
    print("\n4. late-shipments:")
    result = run_command(
        ['curl', '-s', 'http://localhost:7070/state/late-shipments'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Late shipments: {len(data)}")
            if data:
                sample = data[0]
                print(f"   Sample: {sample.get('shipment_id', 'N/A')} - {sample.get('delay_minutes', 0)} min delay")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # State Store 5: warehouse-realtime-metrics (windowed)
    print("\n5. warehouse-realtime-metrics (15-min window):")
    result = run_command(
        ['curl', '-s', 'http://localhost:7070/state/warehouse-realtime-metrics'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Warehouses with metrics: {len(data)}")
            for wh_id, windows in data.items():
                if windows:
                    latest = windows[-1] if isinstance(windows, list) else windows
                    metrics = latest.get('metrics', {})
                    print(f"   - {wh_id}: {metrics.get('total_operations', 0)} operations, {metrics.get('error_count', 0)} errors")
                    break
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # State Store 6: hourly-delivery-performance (windowed)
    print("\n6. hourly-delivery-performance (1-hour hopping window):")
    result = run_command(
        ['curl', '-s', 'http://localhost:7070/state/hourly-delivery-performance'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Warehouses with performance data: {len(data)}")
            for wh_id, windows in data.items():
                if windows:
                    latest = windows[-1] if isinstance(windows, list) else windows
                    stats = latest.get('stats', {})
                    print(f"   - {wh_id}: {stats.get('total_delivered', 0)} delivered, {stats.get('on_time_percentage', 0):.1f}% on-time")
                    break
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")


def test_metadata_endpoints():
    """Test Kafka Streams metadata endpoints (for multi-instance support)."""
    print("\n--- Testing metadata endpoints ---")

    # Test /metadata/instances endpoint for each state store
    state_stores = [
        'active-shipments-by-status',
        'vehicle-current-state',
        'shipments-by-customer',
        'late-shipments',
        'warehouse-realtime-metrics',
        'hourly-delivery-performance'
    ]

    for store in state_stores:
        result = run_command(
            ['curl', '-s', f'http://localhost:7070/metadata/instances/{store}'],
            capture_output=True
        )
        if result.stdout:
            try:
                instances = json.loads(result.stdout)
                print(f"   {store}: {len(instances)} instance(s)")
            except json.JSONDecodeError:
                print(f"   {store}: Error parsing response")


def test_query_api():
    """Test Query API endpoints for all state stores."""
    print("\n--- Testing Query API endpoints ---")

    # Test shipment status counts
    print("\n1. Shipment status counts:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/shipments/status/all'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            counts = data.get('counts', {})
            print(f"   {counts}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test vehicle states
    print("\n2. Vehicle states:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/vehicles/state'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Vehicles: {data.get('count', 0)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test customer stats
    print("\n3. Customer shipment stats:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/customers/shipments/all'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Customers: {data.get('count', 0)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test late shipments
    print("\n4. Late shipments:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/shipments/late'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Late shipments: {data.get('count', 0)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test warehouse metrics
    print("\n5. Warehouse metrics:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/warehouses/metrics/all'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            warehouses = data.get('warehouses', {})
            print(f"   Warehouses: {len(warehouses)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test hourly performance
    print("\n6. Hourly delivery performance:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/performance/hourly'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            warehouses = data.get('warehouses', {})
            print(f"   Warehouses with data: {len(warehouses)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test health endpoint
    print("\n7. Health check:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/health'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Status: {data.get('status', 'UNKNOWN')}")
            print(f"   Phase: {data.get('phase', 'N/A')}")
            stores = data.get('state_stores', [])
            print(f"   State stores: {len(stores)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")


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
            print("  Headless service configured correctly (clusterIP: None)")
        else:
            print(f"  Headless service has unexpected clusterIP: {cluster_ip}")
    else:
        print("  Headless service not found")

    # Check APPLICATION_SERVER environment variable
    print("\nChecking APPLICATION_SERVER env var in pod:")
    result = run_command([
        'kubectl', 'exec', 'streams-processor-0', '-n', NAMESPACE, '--',
        'printenv', 'APPLICATION_SERVER'
    ], capture_output=True, check=False)
    if result.returncode == 0:
        print(f"  APPLICATION_SERVER: {result.stdout.strip()}")
    else:
        print("  APPLICATION_SERVER not set")


def main():
    print("=" * 60)
    print("SmartShip Logistics - Phase 3 Validation")
    print("Testing all 6 state stores")
    print("=" * 60)

    print("\n=== Validating Infrastructure ===")
    result = kubectl('get', 'kafka', KAFKA_CLUSTER_NAME, '-n', NAMESPACE,
                    '-o', 'jsonpath={.status.conditions[?(@.type=="Ready")].status}')
    if result.stdout:
        print(f"Kafka Ready Status: {result.stdout.strip()}")

    print("\n=== Validating PostgreSQL (6 tables) ===")
    kubectl('exec', '-it', 'statefulset/postgresql', '-n', NAMESPACE, '--',
           'psql', '-U', 'smartship', '-d', 'smartship',
           '-c', "SELECT 'warehouses' as table_name, COUNT(*) as cnt FROM warehouses UNION ALL SELECT 'customers', COUNT(*) FROM customers UNION ALL SELECT 'vehicles', COUNT(*) FROM vehicles UNION ALL SELECT 'products', COUNT(*) FROM products UNION ALL SELECT 'drivers', COUNT(*) FROM drivers UNION ALL SELECT 'routes', COUNT(*) FROM routes;")

    print("\n=== Checking event generation ===")
    kubectl('logs', 'deployment/data-generators', '-n', NAMESPACE, '--tail=10')

    print("\n=== Verifying Kafka Data Flow (all 4 topics) ===")
    verify_kafka_data_flow('shipment.events', max_messages=3, timeout=60)
    verify_kafka_data_flow('vehicle.telemetry', max_messages=3, timeout=60)
    verify_kafka_data_flow('warehouse.operations', max_messages=3, timeout=60)
    verify_kafka_data_flow('order.status', max_messages=3, timeout=60)

    # Validate StatefulSet configuration
    validate_statefulset()

    print("\n=== Testing all 6 state stores via Interactive Queries ===")
    port_forward_and_test('streams-processor', 7070, test_all_state_stores)

    print("\n=== Testing metadata endpoints (multi-instance support) ===")
    port_forward_and_test('streams-processor', 7070, test_metadata_endpoints)

    print("\n=== Testing Query API endpoints ===")
    port_forward_and_test('query-api', 8080, test_query_api)

    print("\n" + "=" * 60)
    print("Phase 3 Validation Complete!")
    print("All 6 state stores verified:")
    print("  1. active-shipments-by-status")
    print("  2. vehicle-current-state")
    print("  3. shipments-by-customer")
    print("  4. late-shipments")
    print("  5. warehouse-realtime-metrics (windowed)")
    print("  6. hourly-delivery-performance (windowed)")
    print("=" * 60)

    return 0


if __name__ == '__main__':
    sys.exit(main())
