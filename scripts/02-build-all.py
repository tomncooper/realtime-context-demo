#!/usr/bin/env python3
"""Build all SmartShip modules and container images."""
import os
import subprocess
import sys
from common import run_command, setup_container_runtime, get_minikube_env, CONTAINER_RUNTIME


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
    print("=" * 60)
    print("SmartShip Logistics - Build All Modules")
    print("=" * 60)

    runtime_path = setup_container_runtime()

    print("\n=== Building schemas and common modules ===")
    run_command(['mvn', 'clean', 'install', '-pl', 'schemas,common'])

    print("\n=== Building data-generators ===")
    run_command(['mvn', 'clean', 'package', '-pl', 'data-generators'])

    print("\n=== Building streams-processor ===")
    run_command(['mvn', 'clean', 'package', '-pl', 'streams-processor'])

    print("\n=== Building query-api (JVM mode) ===")
    os.chdir('query-api')
    run_command(['mvn', 'clean', 'package'])
    os.chdir('..')

    print("\n=== Building Container Images ===")

    # Setup environment for container builds
    minikube_env = get_minikube_env(CONTAINER_RUNTIME)
    build_env = os.environ.copy()
    build_env.update(minikube_env)

    if CONTAINER_RUNTIME == 'podman':
        # For podman, build locally and load into minikube
        print("Using Podman for container builds...")

        # Build with Jib using podman
        run_command([
            'mvn', 'compile', 'jib:dockerBuild',
            '-pl', 'data-generators,streams-processor',
            f'-Djib.dockerClient.executable={runtime_path}'
        ])

        # Build Quarkus with Jib using podman
        os.chdir('query-api')
        run_command([
            'mvn', 'package',
            '-Dquarkus.container-image.build=true',
            f'-Djib.dockerClient.executable={runtime_path}'
        ])
        os.chdir('..')

        # Verify images exist in podman
        print("\n=== Verifying images in podman ===")
        run_command(['podman', 'images', 'smartship/*'])

        # Save and load images into minikube (podman workflow)
        print("\n=== Loading images into minikube ===")
        image_list = []
        for image in ['data-generators', 'streams-processor', 'query-api']:
            image_name = f'smartship/{image}:latest'
            image_list.append(image_name)
            print(f"Saving and loading {image_name}...")

            # Save image to tar file
            tar_file = f'/tmp/{image}.tar'
            run_command(['podman', 'save', '-o', tar_file, image_name])

            # Load tar file into minikube
            run_command(['minikube', 'image', 'load', tar_file])

            # Clean up tar file
            run_command(['rm', '-f', tar_file])

        # Retag images to remove localhost prefix added by minikube
        retag_minikube_images(image_list)

        # Verify images in minikube
        print("\n=== Verifying images in minikube ===")
        run_command(
            ['minikube', 'image', 'ls', '--format', 'table'], 
            capture_output=False, 
            check=False
        )
    else:
        # For docker, use minikube's docker daemon
        print("Using Docker with minikube's docker daemon...")

        # Set minikube docker env
        for key, value in minikube_env.items():
            os.environ[key] = value

        run_command(['mvn', 'compile', 'jib:dockerBuild',
                    '-pl', 'data-generators,streams-processor'])

        os.chdir('query-api')
        run_command(['mvn', 'package', '-Dquarkus.container-image.build=true'])
        os.chdir('..')

    print("\n" + "=" * 60)
    print("Build complete!")
    print(f"Container runtime used: {runtime_path}")
    print("=" * 60)

    return 0


if __name__ == '__main__':
    sys.exit(main())
