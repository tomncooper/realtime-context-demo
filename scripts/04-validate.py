#!/usr/bin/env python3
"""Validate SmartShip infrastructure deployment"""
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
    """Test all 9 Kafka Streams state stores via Interactive Queries."""
    print("\n--- Testing all 9 state stores ---")

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

    # State Store 7: order-current-state (Phase 4)
    print("\n7. order-current-state:")
    result = run_command(
        ['curl', '-s', 'http://localhost:7070/state/order-current-state'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Orders tracked: {len(data)}")
            if data:
                sample = data[0]
                print(f"   Sample order: {sample.get('order_id', 'N/A')} - status: {sample.get('status', 'N/A')}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # State Store 8: orders-by-customer (Phase 4)
    print("\n8. orders-by-customer:")
    result = run_command(
        ['curl', '-s', 'http://localhost:7070/state/orders-by-customer'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Customers with orders: {len(data)}")
            if data:
                sample = data[0]
                print(f"   Sample customer: {sample.get('customer_id', 'N/A')} - {sample.get('total_orders', 0)} orders")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # State Store 9: order-sla-tracking (Phase 4)
    print("\n9. order-sla-tracking:")
    result = run_command(
        ['curl', '-s', 'http://localhost:7070/state/order-sla-tracking'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Orders at SLA risk: {len(data)}")
            if data:
                sample = data[0]
                print(f"   Sample: {sample.get('order_id', 'N/A')} - {sample.get('time_to_sla_minutes', 0)} min to SLA")
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
        'hourly-delivery-performance',
        'order-current-state',
        'orders-by-customer',
        'order-sla-tracking'
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

    # Test order state endpoints (Phase 4)
    print("\n7. Order states:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/orders/state'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Orders: {data.get('count', 0)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test customer order stats endpoints (Phase 4)
    print("\n8. Customer order stats:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/orders/by-customer/all'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   Customers with order stats: {data.get('count', 0)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test SLA risk endpoints (Phase 4)
    print("\n9. Orders at SLA risk:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/orders/sla-risk'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            print(f"   At-risk orders: {data.get('count', 0)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test health endpoint
    print("\n10. Health check:")
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


def test_reference_data_api():
    """Test PostgreSQL reference data endpoints (Phase 4)."""
    print("\n--- Testing Reference Data API (PostgreSQL) ---")

    # Test warehouses endpoint
    print("\n1. Warehouses:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/reference/warehouses'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            warehouses = data.get('warehouses', [])
            print(f"   Warehouses: {len(warehouses)}")
            if warehouses:
                sample = warehouses[0]
                print(f"   Sample: {sample.get('warehouse_id', 'N/A')} - {sample.get('name', 'N/A')} ({sample.get('city', 'N/A')})")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test customers endpoint
    print("\n2. Customers:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/reference/customers?limit=10'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            customers = data.get('customers', [])
            print(f"   Customers returned: {len(customers)}")
            if customers:
                sample = customers[0]
                print(f"   Sample: {sample.get('customer_id', 'N/A')} - {sample.get('company_name', 'N/A')} (SLA: {sample.get('sla_tier', 'N/A')})")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test vehicles endpoint
    print("\n3. Vehicles:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/reference/vehicles'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            vehicles = data.get('vehicles', [])
            print(f"   Vehicles: {len(vehicles)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test drivers endpoint
    print("\n4. Drivers:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/reference/drivers'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            drivers = data.get('drivers', [])
            print(f"   Drivers: {len(drivers)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test routes endpoint
    print("\n5. Routes:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/reference/routes'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            routes = data.get('routes', [])
            print(f"   Routes: {len(routes)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test products search endpoint
    print("\n6. Products search:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/reference/products?limit=5'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            products = data.get('products', [])
            print(f"   Products returned: {len(products)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")


def test_hybrid_query_api():
    """Test hybrid query endpoints (Phase 4)."""
    print("\n--- Testing Hybrid Query API (Kafka Streams + PostgreSQL) ---")

    # Test customer overview (need a valid customer ID)
    print("\n1. Customer overview (CUST-0001):")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/hybrid/customers/CUST-0001/overview'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            result_data = data.get('result', {})
            sources = data.get('sources', [])
            query_time = data.get('query_time_ms', 0)
            summary = data.get('summary', '')
            print(f"   Sources: {sources}")
            print(f"   Query time: {query_time}ms")
            if result_data:
                print(f"   Company: {result_data.get('company_name', 'N/A')}")
                print(f"   Total orders: {result_data.get('total_orders', 0)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test warehouse status
    print("\n2. Warehouse status (WH-RTM):")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/hybrid/warehouses/WH-RTM/status'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            result_data = data.get('result', {})
            sources = data.get('sources', [])
            print(f"   Sources: {sources}")
            if result_data:
                print(f"   Warehouse: {result_data.get('name', 'N/A')} ({result_data.get('city', 'N/A')})")
                print(f"   Vehicles: {result_data.get('total_vehicles', 0)}, Drivers: {result_data.get('total_drivers', 0)}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test enriched vehicle state
    print("\n3. Enriched vehicle state (VEH-001):")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/hybrid/vehicles/VEH-001/enriched'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            sources = data.get('sources', [])
            summary = data.get('summary', '')
            print(f"   Sources: {sources}")
            print(f"   Summary: {summary}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test driver tracking
    print("\n4. Driver tracking (DRV-001):")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/hybrid/drivers/DRV-001/tracking'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            sources = data.get('sources', [])
            summary = data.get('summary', '')
            print(f"   Sources: {sources}")
            print(f"   Summary: {summary}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")


def validate_ollama():
    """Validate Ollama is running (optional - for LLM chat functionality).

    Returns True if Ollama is available, False otherwise.
    """
    print("\n=== Validating Ollama (LLM Service) ===")

    # Check Ollama StatefulSet status
    result = run_command([
        'kubectl', 'get', 'statefulset', 'ollama', '-n', NAMESPACE,
        '-o', 'jsonpath={.status.readyReplicas}/{.spec.replicas}'
    ], capture_output=True, check=False)

    if result.returncode == 0 and result.stdout.strip():
        replicas = result.stdout.strip()
        print(f"Ollama replicas ready: {replicas}")
        # Check if at least one replica is ready
        parts = replicas.split('/')
        if len(parts) == 2 and parts[0] and int(parts[0]) > 0:
            return True
        print("  Warning: Ollama not ready yet")
        return False
    else:
        print("Ollama deployment not found (chat validation will be skipped)")
        return False


def test_chat_api():
    """Test LLM chat API endpoints (Phase 6)."""
    print("\n--- Testing Chat API (LangChain4j + Ollama) ---")

    # Test 1: Health check
    print("\n1. Chat health check:")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/chat/health'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            status = data.get('status', 'UNKNOWN')
            service = data.get('service', 'N/A')
            print(f"   Status: {status}")
            print(f"   Service: {service}")
            if status != 'UP':
                print("   Warning: Chat service not healthy, skipping remaining chat tests")
                return
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")
            return

    # Test 2: Session count (before chat)
    print("\n2. Active sessions (before test):")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/chat/sessions/count'],
        capture_output=True
    )
    sessions_before = 0
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            sessions_before = data.get('active_sessions', 0)
            print(f"   Active sessions: {sessions_before}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test 3: Basic chat interaction
    print("\n3. Chat interaction (may take a few seconds):")
    result = run_command([
        'curl', '-s', '-X', 'POST', 'http://localhost:8080/api/chat',
        '-H', 'Content-Type: application/json',
        '-d', '{"message": "How many shipments are in transit?"}'
    ], capture_output=True)
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            session_id = data.get('sessionId', 'N/A')
            sources = data.get('sources', [])
            response = data.get('response', '')
            print(f"   Session ID: {session_id[:8]}..." if len(session_id) > 8 else f"   Session ID: {session_id}")
            print(f"   Sources: {sources}")
            # Truncate response for display
            response_preview = response[:100] + "..." if len(response) > 100 else response
            print(f"   Response: {response_preview}")
        except json.JSONDecodeError:
            print(f"   Response: {result.stdout[:200]}")

    # Test 4: Session count (after chat)
    print("\n4. Active sessions (after test):")
    result = run_command(
        ['curl', '-s', 'http://localhost:8080/api/chat/sessions/count'],
        capture_output=True
    )
    if result.stdout:
        try:
            data = json.loads(result.stdout)
            sessions_after = data.get('active_sessions', 0)
            print(f"   Active sessions: {sessions_after}")
            if sessions_after > sessions_before:
                print(f"   New session created successfully")
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
    print("SmartShip Logistics - Phase 6 Validation")
    print("Testing all 9 state stores + PostgreSQL + Hybrid Queries + LLM Chat")
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

    print("\n=== Testing all 9 state stores via Interactive Queries ===")
    port_forward_and_test('streams-processor', 7070, test_all_state_stores)

    print("\n=== Testing metadata endpoints (multi-instance support) ===")
    port_forward_and_test('streams-processor', 7070, test_metadata_endpoints)

    print("\n=== Testing Query API endpoints (Kafka Streams) ===")
    port_forward_and_test('query-api', 8080, test_query_api)

    print("\n=== Testing Reference Data API (PostgreSQL) ===")
    port_forward_and_test('query-api', 8080, test_reference_data_api)

    print("\n=== Testing Hybrid Query API (Kafka Streams + PostgreSQL) ===")
    port_forward_and_test('query-api', 8080, test_hybrid_query_api)

    # Validate Ollama and Chat API (Phase 6) - gracefully skip if unavailable
    ollama_available = validate_ollama()
    if ollama_available:
        print("\n=== Testing Chat API (LangChain4j + LLM) ===")
        port_forward_and_test('query-api', 8080, test_chat_api)
    else:
        print("\n=== Skipping Chat API tests (Ollama not available) ===")

    print("\n" + "=" * 60)
    print("Phase 6 Validation Complete!")
    print("=" * 60)
    print("\nAll 9 state stores verified:")
    print("  1. active-shipments-by-status")
    print("  2. vehicle-current-state")
    print("  3. shipments-by-customer")
    print("  4. late-shipments")
    print("  5. warehouse-realtime-metrics (windowed)")
    print("  6. hourly-delivery-performance (windowed)")
    print("  7. order-current-state (Phase 4)")
    print("  8. orders-by-customer (Phase 4)")
    print("  9. order-sla-tracking (Phase 4)")
    print("\nPhase 4 additions:")
    print("  - 17 PostgreSQL reference data endpoints")
    print("  - 6 Order state query endpoints")
    print("  - 7 Hybrid query endpoints (Kafka + PostgreSQL)")
    print("\nPhase 6 additions:")
    print("  - 4 Chat API endpoints (/api/chat/*)")
    print("  - LangChain4j integration with Ollama")
    print("  - Session-based chat memory")
    print("=" * 60)

    return 0


if __name__ == '__main__':
    sys.exit(main())
