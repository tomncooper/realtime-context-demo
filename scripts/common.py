#!/usr/bin/env python3
"""Common utilities for deployment scripts."""
import os
import shutil
import subprocess
import sys

from pprint import pprint
from typing import List

# Container runtime detection (podman is default)
CONTAINER_RUNTIME = os.getenv('CONTAINER_RUNTIME', 'podman')

KAFKA_CLUSTER_NAME = "events-cluster"
NAMESPACE = "smartship"


def run_command(
        cmd: List[str],
        check: bool = True,
        capture_output: bool = False,
        text: bool = True
) -> subprocess.CompletedProcess:
    """Execute a shell command."""
    print(f"Running: {' '.join(cmd)}")
    result = subprocess.run(cmd, check=check, capture_output=capture_output, text=text)
    if capture_output and result.stdout and text:
        pprint(result.stdout)
    return result


def kubectl(*args: str) -> subprocess.CompletedProcess:
    """Execute kubectl command."""
    return run_command(['kubectl'] + list(args))


def wait_for_condition(
        resource_type: str,
        resource_name: str,
        condition: str,
        namespace: str = NAMESPACE,
        timeout: int = 300
) -> None:
    """Wait for Kubernetes resource condition."""
    print(f"Waiting for {resource_type}/{resource_name} to be {condition}...")
    kubectl('wait', f'{resource_type}/{resource_name}',
            f'--for=condition={condition}',
            f'--timeout={timeout}s',
            '-n', namespace)


def wait_for_statefulset_ready(name: str, replicas: int = 1,
                               namespace: str = NAMESPACE, timeout: int = 300) -> None:
    """Wait for StatefulSet pods to be ready.

    StatefulSets don't have the same 'Available' condition as Deployments.
    We need to wait for all pods to be ready using pod label selector.

    Args:
        name: Name of the StatefulSet
        replicas: Expected number of replicas
        namespace: Kubernetes namespace
        timeout: Timeout in seconds
    """
    import time

    print(f"Waiting for StatefulSet {name} to have {replicas} ready pod(s)...")

    start_time = time.time()
    while time.time() - start_time < timeout:
        try:
            # Check StatefulSet status
            result = run_command([
                'kubectl', 'get', 'statefulset', name, '-n', namespace,
                '-o', 'jsonpath={.status.readyReplicas}'
            ], capture_output=True, check=False)

            ready_replicas = result.stdout.strip() if result.stdout else '0'
            ready_count = int(ready_replicas) if ready_replicas else 0

            if ready_count >= replicas:
                print(f"✓ StatefulSet {name} has {ready_count}/{replicas} pods ready")
                return

            print(f"  StatefulSet {name}: {ready_count}/{replicas} pods ready, waiting...")
            time.sleep(5)

        except Exception as e:
            print(f"  Error checking StatefulSet status: {e}")
            time.sleep(5)

    raise TimeoutError(f"Timeout waiting for StatefulSet {name} to be ready")


def setup_container_runtime() -> str:
    """Configure container runtime for minikube and return absolute path."""
    runtime = CONTAINER_RUNTIME
    print(f"Detecting container runtime: {runtime}")

    # Verify runtime is available and get absolute path
    runtime_path = shutil.which(runtime)

    if runtime_path is None:
        print(
            f"ERROR: {runtime} not found in PATH. "
            f"Please install {runtime} or set CONTAINER_RUNTIME environment variable."
        )
        sys.exit(1)

    print(f"Using container runtime: {runtime_path}")
    return runtime_path


def get_minikube_env(runtime: str) -> dict:
    """Get minikube docker/podman environment variables."""

    if runtime == 'podman':
        # For podman, we'll use minikube image load
        return {}

    # For docker, use minikube docker-env
    result = run_command(
        ['minikube', 'docker-env', '--shell', 'bash'],
        capture_output=True
    )
    env_vars = {}
    for line in result.stdout.splitlines():
        if line.startswith('export'):
            parts = line.replace('export ', '').split('=')
            if len(parts) == 2:
                key, value = parts
                env_vars[key] = value.strip('"')
    return env_vars


def verify_kafka_data_flow(
        topic: str,
        max_messages: int = 5,
        timeout: int = 60,
        pod_name: str = f'{KAFKA_CLUSTER_NAME}-dual-role-0',
        namespace: str = NAMESPACE
) -> None:

    """Verify data is flowing through a Kafka topic.

    Args:
        topic: Name of the Kafka topic to check
        max_messages: Number of messages to attempt to read
        timeout: Maximum time to wait for messages (seconds)
        namespace: The namespace the kafka broker pod is in
        pod_name: The pod you want to run console consumer script in
    """

    print(f"Waiting for Kafka pod {pod_name} to be ready...")
    wait_for_condition('pod', pod_name, 'Ready', namespace=namespace, timeout=300)

    print(f"Verifying data flow on topic '{topic}'...")
    print(f"Attempting to read up to {max_messages} messages (timeout: {timeout}s)...")

    # Use kubectl exec to run kafka-console-consumer
    cmd = [
        'kubectl', 'exec', '-it', pod_name, '-n', namespace, '--',
        '/opt/kafka/bin/kafka-console-consumer.sh',
        '--bootstrap-server', 'localhost:9092',
        '--topic', topic,
        '--from-beginning',
        '--max-messages', str(max_messages),
        '--timeout-ms', str(timeout * 1000)
    ]

    try:
        # Use text=False to handle binary Avro data
        result = run_command(cmd, capture_output=True, check=False, text=False)

        # Check if we got any messages (binary data)
        if result.returncode == 0 and result.stdout:
            # Count messages by looking for Avro data (any non-empty output indicates messages)
            # We can't decode Avro here, but we can verify data exists
            byte_count = len(result.stdout)
            print(f"✓ Successfully verified data flow: {byte_count} bytes of Avro data received")
            print(f"  Topic '{topic}' is receiving data from generators")

            # Try to decode stderr as text for any consumer output/stats
            if result.stderr:
                try:
                    stderr_text = result.stderr.decode('utf-8', errors='ignore')
                    if 'Processed a total of' in stderr_text:
                        print(f"  {stderr_text.strip()}")
                except (UnicodeDecodeError, AttributeError):
                    pass
        else:
            print(f"✗ No messages received from topic '{topic}'")
            print("  This might indicate data generators are not producing events")
            if result.stderr:
                stderr_text = result.stderr.decode('utf-8', errors='ignore')
                print(f"  Error output: {stderr_text}")
            raise RuntimeError(f"Kafka data flow verification failed for topic: {topic}")

    except RuntimeError:
        raise
    except Exception as e:
        print(f"Error verifying Kafka data flow: {e}")
        raise
