#!/usr/bin/env python3
"""Build all SmartShip modules and container images."""
import argparse
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from common import run_command, setup_container_runtime, CONTAINER_RUNTIME


def get_target_platform() -> str:
    """Get target platform for container builds from environment variable."""
    target_arch = os.getenv('TARGET_ARCH', 'amd64')
    return f'linux/{target_arch}'


def get_ocp_registry_config() -> dict:
    """Get OpenShift registry configuration from current context or environment."""
    # Check if oc CLI is available
    oc_path = subprocess.run(['which', 'oc'], capture_output=True, text=True)
    if oc_path.returncode != 0:
        print("Error: 'oc' CLI not found. Please install OpenShift CLI.")
        sys.exit(1)

    # Check if user is logged in
    login_check = subprocess.run(['oc', 'whoami'], capture_output=True, text=True)
    if login_check.returncode != 0:
        print("Error: Not logged in to OpenShift. Please run 'oc login' first.")
        sys.exit(1)

    # Always use smartship project
    project = 'smartship'

    # Try to get registry URL from environment first
    registry_url = os.getenv('OCP_REGISTRY_URL')

    # If not set, try to detect from OpenShift routes
    if not registry_url:
        print("Auto-detecting OpenShift registry URL...")

        # Try to get the image registry route
        registry_route_cmd = subprocess.run([
            'oc', 'get', 'route', 'default-route',
            '-n', 'openshift-image-registry',
            '--template={{.spec.host}}'
        ], capture_output=True, text=True)

        if registry_route_cmd.returncode == 0 and registry_route_cmd.stdout.strip():
            registry_url = registry_route_cmd.stdout.strip()
            print(f"Detected registry URL: {registry_url}")

    if not registry_url:
        print("Error: Could not detect OpenShift registry URL.")
        print("Please set OCP_REGISTRY_URL environment variable:")
        print("export OCP_REGISTRY_URL=default-route-openshift-image-registry.apps.cluster.example.com")
        sys.exit(1)

    # Get authentication token
    token_cmd = subprocess.run(['oc', 'whoami', '-t'], capture_output=True, text=True)
    if token_cmd.returncode != 0:
        print("Error: Could not get OpenShift token.")
        sys.exit(1)

    token = token_cmd.stdout.strip()

    return {
        'registry_url': registry_url,
        'project': project,
        'token': token,
        'user': 'user'  # Static username - token provides authentication
    }


def parse_args() -> argparse.Namespace:
    """Parse command-line arguments."""
    parser = argparse.ArgumentParser(
        description='Build SmartShip modules and container images'
    )
    parser.add_argument(
        '--native', action='store_true',
        help='Build query-api as GraalVM native image (default: JVM mode)'
    )
    parser.add_argument(
        '--skip-tests', action='store_true',
        help='Skip running tests during build'
    )
    parser.add_argument(
        '--load-minikube', action='store_true',
        help='Load container images into minikube'
    )
    parser.add_argument(
        '--push-ocp', action='store_true',
        help='Push container images to OpenShift registry'
    )
    return parser.parse_args()


def get_project_version() -> str:
    """Extract project version from parent pom.xml."""
    pom_path = 'pom.xml'
    try:
        tree = ET.parse(pom_path)
        root = tree.getroot()
        # Handle Maven namespace
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        version_elem = root.find('m:version', ns)
        if version_elem is not None and version_elem.text:
            return version_elem.text
    except Exception as e:
        print(f"Warning: Could not parse pom.xml: {e}")
    return '1.0.0-SNAPSHOT'  # fallback


def ensure_prerequisites_built(native: bool = False) -> None:
    """Ensure schemas and common modules are built.

    Args:
        native: If True, always rebuild with native profile (Java 21)
    """
    if native:
        # For native builds, always clean and rebuild with native profile
        print("\n=== Building prerequisite modules (schemas, common) for native ===")
        run_command(['mvn', 'clean', 'install', '-pl', 'schemas,common', '-am', '-Dnative', '-DskipTests'])
    else:
        schemas_built = os.path.isdir('schemas/target/classes')
        common_built = os.path.isdir('common/target/classes')

        if not schemas_built or not common_built:
            print("\n=== Building prerequisite modules (schemas, common) ===")
            run_command(['mvn', 'clean', 'install', '-pl', 'schemas,common', '-DskipTests', '-am'])


def build_query_api_native(skip_tests: bool, runtime_path: str, target_platform: str) -> None:
    """Build query-api as a GraalVM native image with container."""
    print("\n=== Building query-api (Native mode) ===")

    os.chdir('query-api')

    # Use clean package to ensure fresh compilation with Java 21
    # Always build container image along with native executable
    quarkus_jib_executable = f'-Dquarkus.jib.docker-executable-name={CONTAINER_RUNTIME}'
    mvn_args = [
        'mvn', 'clean', 'package',
        '-Dnative',
        '-Dquarkus.native.container-build=true',
        '-Dquarkus.container-image.build=true',
        f'-Djib.dockerClient.executable={runtime_path}',
        f'-Djib.from.platforms={target_platform}',
        f'-Djib.to.platforms={target_platform}',
        quarkus_jib_executable
    ]

    if skip_tests:
        mvn_args.append('-DskipTests')

    run_command(mvn_args)
    os.chdir('..')


def verify_native_build() -> bool:
    """Verify native executable was built and display info."""
    version = get_project_version()
    native_exec = f'query-api/target/query-api-{version}-runner'

    if os.path.isfile(native_exec):
        size = os.path.getsize(native_exec)
        size_mb = size / (1024 * 1024)
        print(f"\n✓ Native executable: {native_exec}")
        print(f"  Size: {size_mb:.1f} MB")
        return True
    else:
        print(f"\n✗ Warning: Native executable not found at {native_exec}")
        return False


def build_container_images(runtime_path: str, target_platform: str, native_query_api: bool = False) -> None:
    """Build container images for all services.

    Args:
        runtime_path: Path to container runtime (podman/docker)
        target_platform: Target platform for container images (e.g., 'linux/arm64')
        native_query_api: If True, query-api container was already built during native build
    """
    print("\n=== Building Container Images ===")
    print(f"Target platform: {target_platform}")

    if CONTAINER_RUNTIME == 'podman':
        print("Using Podman for container builds...")
    else:
        print("Using Docker for container builds...")

    # Build data-generators container image
    print("\n--- Building data-generators container ---")
    run_command([
        'mvn', 'clean', 'compile', '-pl', 'data-generators', '-am'
    ])
    run_command([
        'mvn', 'compile', 'jib:dockerBuild', '-pl', 'data-generators',
        f'-Djib.dockerClient.executable={runtime_path}',
        f'-Djib.from.platforms={target_platform}',
        f'-Djib.to.platforms={target_platform}'
    ])

    # Build streams-processor container image
    print("\n--- Building streams-processor container ---")
    run_command([
        'mvn', 'clean', 'compile', '-pl', 'streams-processor', '-am'
    ])
    run_command([
        'mvn', 'compile', 'jib:dockerBuild', '-pl', 'streams-processor',
        f'-Djib.dockerClient.executable={runtime_path}',
        f'-Djib.from.platforms={target_platform}',
        f'-Djib.to.platforms={target_platform}'
    ])

    # Build query-api container image (only if JVM mode - native builds it already)
    if not native_query_api:
        print("\n--- Building query-api container ---")
        os.chdir('query-api')
        quarkus_jib_executable = f'-Dquarkus.jib.docker-executable-name={CONTAINER_RUNTIME}'
        run_command([
            'mvn', 'package',
            '-Dquarkus.container-image.build=true',
            f'-Djib.dockerClient.executable={runtime_path}',
            f'-Djib.from.platforms={target_platform}',
            f'-Djib.to.platforms={target_platform}',
            quarkus_jib_executable
        ])
        os.chdir('..')

    # Verify images exist
    print("\n=== Verifying container images ===")
    if CONTAINER_RUNTIME == 'podman':
        run_command(['podman', 'images', 'smartship/*'])
    else:
        run_command(['docker', 'images', 'smartship/*'])


def load_images_to_minikube(images: list[str]) -> None:
    """Load container images into minikube.

    Args:
        images: List of image names (e.g., ['data-generators', 'query-api'])
    """
    print("\n=== Loading images into minikube ===")
    image_list = []

    for image in images:
        image_name = f'smartship/{image}:latest'
        image_list.append(image_name)
        print(f"Saving and loading {image_name}...")

        tar_file = f'/tmp/{image}.tar'

        if CONTAINER_RUNTIME == 'podman':
            run_command(['podman', 'save', '-o', tar_file, image_name])
        else:
            run_command(['docker', 'save', '-o', tar_file, image_name])

        run_command(['minikube', 'image', 'load', tar_file])
        run_command(['rm', '-f', tar_file])

    # Retag images to remove localhost prefix (needed for both runtimes)
    retag_minikube_images(image_list)

    print("\n=== Verifying images in minikube ===")
    run_command(['minikube', 'image', 'ls', '--format', 'table'],
                capture_output=False, check=False)


def retag_minikube_images(images: list[str]) -> None:
    """Remove localhost prefix from images in minikube (podman issue).

    Args:
        images: List of image names without localhost prefix 
                (e.g., 'smartship/data-generators:latest')
    """
    print("\n=== Retagging images in minikube (removing localhost prefix) ===")

    for image in images:
        localhost_image = f'localhost/{image}'
        print(f"Retagging: {localhost_image} -> {image}")

        # Use minikube image tag to create alias without localhost prefix
        cmd = ['minikube', 'image', 'tag', localhost_image, f"docker.io/{image}"]
        try:
            run_command(cmd)
            print(f"  ✓ Successfully retagged {image}")
        except (subprocess.CalledProcessError, RuntimeError) as e:
            # Don't fail the build, as the localhost-prefixed image still works if we update deployments
            print(f"  ✗ Failed to retag {image}: {e}")


def push_images_to_ocp(images: list[str], ocp_config: dict) -> None:
    """Push container images to OpenShift registry.

    Args:
        images: List of image names (e.g., ['data-generators', 'query-api'])
        ocp_config: OpenShift registry configuration
    """
    print("\n=== Pushing images to OpenShift registry ===")
    print(f"Registry: {ocp_config['registry_url']}")
    print(f"Project: {ocp_config['project']}")
    print(f"Logged in as: {ocp_config['user']}")

    # Verify project exists or create it
    project_check = subprocess.run(['oc', 'get', 'project', ocp_config['project']],
                                 capture_output=True, text=True)
    if project_check.returncode != 0:
        print(f"Creating project: {ocp_config['project']}")
        run_command(['oc', 'new-project', ocp_config['project']])
    else:
        print(f"Using existing project: {ocp_config['project']}")

    # Login to registry using OpenShift token
    print(f"\n--- Authenticating with OpenShift registry ---")
    if CONTAINER_RUNTIME == 'podman':
        login_cmd = [
            'podman', 'login',
            '--tls-verify=false',
            '-u', ocp_config['user'],
            '-p', ocp_config['token'],
            ocp_config['registry_url']
        ]
    else:
        # Note: Docker may require daemon config for insecure registry
        login_cmd = [
            'docker', 'login',
            '-u', ocp_config['user'],
            '-p', ocp_config['token'],
            ocp_config['registry_url']
        ]

    # Run login command with suppressed output to avoid showing token
    result = subprocess.run(login_cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Error: Failed to authenticate with registry")
        print(f"Registry: {ocp_config['registry_url']}")
        print(f"STDERR: {result.stderr}")
        if result.stdout:
            print(f"STDOUT: {result.stdout}")
        sys.exit(1)
    print("✓ Successfully authenticated with OpenShift registry")

    # Push each image
    for image in images:
        local_image = f'smartship/{image}:latest'
        ocp_image = f'{ocp_config["registry_url"]}/{ocp_config["project"]}/{image}:latest'

        print(f"\n--- Pushing {image} ---")

        # Tag for OCP registry
        if CONTAINER_RUNTIME == 'podman':
            run_command(['podman', 'tag', local_image, ocp_image])
            run_command(['podman', 'push', '--tls-verify=false', ocp_image])
        else:
            run_command(['docker', 'tag', local_image, ocp_image])
            run_command(['docker', 'push', ocp_image])

        print(f"✓ Pushed: {ocp_image}")

    print(f"\n=== OpenShift deployment info ===")
    print("Images are now available in OpenShift registry:")
    for image in images:
        ocp_image = f'{ocp_config["registry_url"]}/{ocp_config["project"]}/{image}:latest'
        print(f"  {ocp_image}")

    print(f"\nTo deploy, update your Kubernetes manifests to use:")
    print(f"  image: {ocp_config['registry_url']}/{ocp_config['project']}/{{module}}:latest")
    print(f"\nOr use internal registry reference:")
    print(f"  image: image-registry.openshift-image-registry.svc:5000/{ocp_config['project']}/{{module}}:latest")


def main():
    args = parse_args()

    print("=" * 60)
    if args.native:
        print("SmartShip Logistics - Build All (Native query-api)")
    else:
        print("SmartShip Logistics - Build All (JVM mode)")
    print("=" * 60)

    runtime_path = setup_container_runtime()

    # Get target platform for container builds
    target_platform = get_target_platform()
    print(f"Target platform: {target_platform}")

    # 1. Build prerequisites (schemas, common)
    ensure_prerequisites_built(native=args.native)

    # 2. Build query-api (native or JVM based on flag)
    if args.native:
        build_query_api_native(args.skip_tests, runtime_path, target_platform)
        verify_native_build()
    else:
        print("\n=== Building query-api (JVM mode) ===")
        os.chdir('query-api')
        run_command(['mvn', 'clean', 'package'])
        os.chdir('..')

    # 3. Build container images for all services
    build_container_images(runtime_path, target_platform, native_query_api=args.native)

    # 4. Load to minikube if requested
    if args.load_minikube:
        load_images_to_minikube(['data-generators', 'streams-processor', 'query-api'])

    # 5. Push to OpenShift registry if requested
    if args.push_ocp:
        ocp_config = get_ocp_registry_config()
        push_images_to_ocp(['data-generators', 'streams-processor', 'query-api'], ocp_config)

    print("\n" + "=" * 60)
    print("Build complete!")
    print(f"Container runtime used: {runtime_path}")
    if args.native:
        print("Query-API: Native image")
    else:
        print("Query-API: JVM mode")

    if args.load_minikube:
        print("Images loaded into minikube")
    elif args.push_ocp:
        print("Images pushed to OpenShift registry")
    else:
        print("Images built locally only")
    print("=" * 60)

    return 0


if __name__ == '__main__':
    sys.exit(main())
