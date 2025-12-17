#!/usr/bin/env python3
"""Build all SmartShip modules and container images."""
import argparse
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from common import run_command, setup_container_runtime, CONTAINER_RUNTIME


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
        run_command(['mvn', 'clean', 'install', '-pl', 'schemas,common', '-Dnative', '-DskipTests'])
    else:
        schemas_built = os.path.isdir('schemas/target/classes')
        common_built = os.path.isdir('common/target/classes')

        if not schemas_built or not common_built:
            print("\n=== Building prerequisite modules (schemas, common) ===")
            run_command(['mvn', 'clean', 'install', '-pl', 'schemas,common', '-DskipTests'])


def build_query_api_native(skip_tests: bool, runtime_path: str) -> None:
    """Build query-api as a GraalVM native image with container."""
    print("\n=== Building query-api (Native mode) ===")

    os.chdir('query-api')

    # Use clean package to ensure fresh compilation with Java 21
    # Always build container image along with native executable
    mvn_args = [
        'mvn', 'clean', 'package',
        '-Dnative',
        '-Dquarkus.native.container-build=true',
        '-Dquarkus.container-image.build=true',
        f'-Djib.dockerClient.executable={runtime_path}'
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


def build_container_images(runtime_path: str, native_query_api: bool = False) -> None:
    """Build container images for all services.

    Args:
        runtime_path: Path to container runtime (podman/docker)
        native_query_api: If True, query-api container was already built during native build
    """
    print("\n=== Building Container Images ===")

    # Build data-generators and streams-processor container images (always JVM)
    if CONTAINER_RUNTIME == 'podman':
        print("Using Podman for container builds...")
    else:
        print("Using Docker for container builds...")

    run_command([
        'mvn', 'compile', 'jib:dockerBuild',
        '-pl', 'data-generators,streams-processor',
        f'-Djib.dockerClient.executable={runtime_path}'
    ])

    # Build query-api container image (only if JVM mode - native builds it already)
    if not native_query_api:
        os.chdir('query-api')
        run_command([
            'mvn', 'package',
            '-Dquarkus.container-image.build=true',
            f'-Djib.dockerClient.executable={runtime_path}'
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


def main():
    args = parse_args()

    print("=" * 60)
    if args.native:
        print("SmartShip Logistics - Build All (Native query-api)")
    else:
        print("SmartShip Logistics - Build All (JVM mode)")
    print("=" * 60)

    runtime_path = setup_container_runtime()

    # 1. Build prerequisites (schemas, common)
    ensure_prerequisites_built(native=args.native)

    # 2. Build data-generators and streams-processor (always JVM)
    print("\n=== Building data-generators ===")
    run_command(['mvn', 'clean', 'package', '-pl', 'data-generators'])

    print("\n=== Building streams-processor ===")
    run_command(['mvn', 'clean', 'package', '-pl', 'streams-processor'])

    # 3. Build query-api (native or JVM based on flag)
    if args.native:
        build_query_api_native(args.skip_tests, runtime_path)
        verify_native_build()
    else:
        print("\n=== Building query-api (JVM mode) ===")
        os.chdir('query-api')
        run_command(['mvn', 'clean', 'package'])
        os.chdir('..')

    # 4. Build container images for all services
    build_container_images(runtime_path, native_query_api=args.native)

    # 5. Load to minikube if requested
    if args.load_minikube:
        load_images_to_minikube(['data-generators', 'streams-processor', 'query-api'])

    print("\n" + "=" * 60)
    print("Build complete!")
    print(f"Container runtime used: {runtime_path}")
    if args.native:
        print("Query-API: Native image")
    else:
        print("Query-API: JVM mode")
    print("=" * 60)

    return 0


if __name__ == '__main__':
    sys.exit(main())
