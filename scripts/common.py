#!/usr/bin/env python3
"""Common utilities for deployment scripts."""
import os
import shutil
import subprocess
import sys
from pathlib import Path
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

    Uses kubectl rollout status which waits quietly until the StatefulSet
    is fully rolled out, rather than polling with repeated get commands.

    Args:
        name: Name of the StatefulSet
        replicas: Expected number of replicas (unused, kept for API compatibility)
        namespace: Kubernetes namespace
        timeout: Timeout in seconds
    """
    print(f"Waiting for StatefulSet {name} to be ready...")
    result = subprocess.run(
        ['kubectl', 'rollout', 'status', f'statefulset/{name}',
         '-n', namespace, f'--timeout={timeout}s'],
        check=False
    )
    if result.returncode != 0:
        raise TimeoutError(f"Timeout waiting for StatefulSet {name} to be ready")
    print(f"✓ StatefulSet {name} is ready")


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
    # Note: Don't use -t flag (TTY) when running non-interactively
    cmd = [
        'kubectl', 'exec', '-i', pod_name, '-n', namespace, '--',
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


def ensure_ollama_models(models: List[str]) -> bool:
    """Ensure Ollama models are available locally, downloading if necessary.

    Args:
        models: List of model names (e.g., ['llama3.2', 'mistral'])

    Returns:
        True if all models are available (downloaded or already present)
    """
    # Check if ollama is installed
    ollama_path = shutil.which('ollama')
    if ollama_path is None:
        print("ERROR: ollama not found in PATH. Please install Ollama first.")
        print("  Visit: https://ollama.ai/download")
        return False

    # Get list of locally available models via ollama list
    result = run_command(['ollama', 'list'], capture_output=True, check=False)
    available_models = set()
    if result.returncode == 0 and result.stdout:
        for line in result.stdout.strip().split('\n')[1:]:  # Skip header row
            if line.strip():
                # Model name is first column, may include :tag
                model_name = line.split()[0].split(':')[0]
                available_models.add(model_name)

    print(f"Locally available models: {available_models if available_models else 'none'}")

    for model in models:
        model_base = model.split(':')[0]  # Handle model:tag format
        if model_base in available_models:
            print(f"✓ Model '{model}' already available locally")
        else:
            print(f"Model '{model}' not found locally, downloading...")
            pull_result = run_command(['ollama', 'pull', model], check=False)
            if pull_result.returncode != 0:
                print(f"ERROR: Failed to download model '{model}'")
                return False
            print(f"✓ Model '{model}' downloaded successfully")

    return True


def upload_ollama_models_to_minikube() -> bool:
    """Copy local Ollama data directory to minikube node for pre-loading.

    This copies the entire Ollama data directory to a hostPath location
    on the minikube node, allowing the Ollama pod to use pre-loaded models
    without downloading them again.

    Checks multiple possible locations for Ollama data:
    - ~/.ollama (user installation)
    - /usr/share/ollama/.ollama (Linux system service, requires sudo)

    Returns:
        True if upload was successful
    """
    # Check multiple possible Ollama data locations
    # For user installation, we can access directly
    # For system service, we need sudo to access
    user_ollama = Path.home() / ".ollama"
    system_ollama = Path("/usr/share/ollama/.ollama")

    ollama_dir = None
    needs_sudo = False

    if user_ollama.exists() and (user_ollama / "models").exists():
        ollama_dir = user_ollama
        needs_sudo = False
    else:
        # Check system location (may need sudo)
        result = run_command(
            ['sudo', 'test', '-d', str(system_ollama / "models")],
            check=False, capture_output=True
        )
        if result.returncode == 0:
            ollama_dir = system_ollama
            needs_sudo = True

    if ollama_dir is None:
        print("ERROR: Ollama data directory not found. Checked:")
        print(f"  - {user_ollama}")
        print(f"  - {system_ollama} (with sudo)")
        return False

    print(f"Found Ollama data at: {ollama_dir}" + (" (requires sudo)" if needs_sudo else ""))

    # Create target directory on minikube node
    result = run_command(
        ['minikube', 'ssh', 'sudo mkdir -p /data/ollama-models'],
        check=False
    )
    if result.returncode != 0:
        print("ERROR: Failed to create directory on minikube node")
        return False

    print("Copying model files (this may take a moment for large models)...")

    if needs_sudo:
        # For system Ollama, we need to use sudo to read the files
        # Create a temporary tarball in /tmp (sudo writes here, then we copy)
        tmp_tar = '/tmp/ollama-models-upload.tar'

        print("  Creating tarball of Ollama data (requires sudo)...")
        # Remove any existing tarball first
        run_command(['sudo', 'rm', '-f', tmp_tar], check=False, capture_output=True)

        result = run_command(
            ['sudo', 'tar', '-cf', tmp_tar, '-C', str(ollama_dir), '.'],
            check=False
        )
        if result.returncode != 0:
            print("ERROR: Failed to create tarball of Ollama data")
            run_command(['sudo', 'rm', '-f', tmp_tar], check=False, capture_output=True)
            return False

        # Make tarball readable by current user for minikube cp
        run_command(['sudo', 'chmod', '644', tmp_tar], check=False)

        print("  Copying tarball to minikube...")
        result = run_command(
            ['minikube', 'cp', tmp_tar, '/tmp/ollama-data.tar'],
            check=False
        )
        if result.returncode != 0:
            print("ERROR: Failed to copy tarball to minikube")
            run_command(['sudo', 'rm', '-f', tmp_tar], check=False, capture_output=True)
            return False

        print("  Extracting on minikube node...")
        result = run_command(
            ['minikube', 'ssh', 'sudo tar -xf /tmp/ollama-data.tar -C /data/ollama-models && sudo rm -f /tmp/ollama-data.tar'],
            check=False
        )
        if result.returncode != 0:
            print("ERROR: Failed to extract tarball on minikube")
            run_command(['sudo', 'rm', '-f', tmp_tar], check=False, capture_output=True)
            return False

        # Clean up local tarball
        run_command(['sudo', 'rm', '-f', tmp_tar], check=False, capture_output=True)
    else:
        # Direct copy for user installation
        src_path = str(ollama_dir)
        result = run_command(
            ['minikube', 'cp', f'{src_path}/.', '/data/ollama-models'],
            check=False
        )
        if result.returncode != 0:
            print("ERROR: Failed to copy models to minikube node")
            return False

    # Set proper permissions for Ollama container (runs as root by default)
    run_command(
        ['minikube', 'ssh', 'sudo chmod -R 755 /data/ollama-models'],
        check=False
    )

    print("✓ Ollama models uploaded to minikube successfully")
    return True
