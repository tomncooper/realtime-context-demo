#!/usr/bin/env python3
"""
Render documentation to PDF.

Requires:
    - npm install -g @mermaid-js/mermaid-cli
    - pandoc
    - weasyprint (pip install weasyprint) - recommended, no LaTeX needed
    - OR texlive with xelatex
"""

import os
import subprocess
import sys
import shutil
from pathlib import Path


def check_command(cmd: str) -> bool:
    """Check if a command is available."""
    return shutil.which(cmd) is not None


def find_chromium() -> str | None:
    """Find system Chromium/Chrome executable."""
    candidates = [
        "chromium-browser",
        "chromium",
        "google-chrome",
        "google-chrome-stable",
        "/usr/bin/chromium-browser",
        "/usr/bin/chromium",
        "/usr/bin/google-chrome",
        "/snap/bin/chromium",
    ]
    for candidate in candidates:
        if shutil.which(candidate):
            return shutil.which(candidate)
    return None


def render_mermaid(input_file: Path, output_file: Path) -> None:
    """Render a Mermaid diagram to PNG."""
    output_file.parent.mkdir(parents=True, exist_ok=True)

    env = os.environ.copy()

    # Try to use system Chromium if PUPPETEER_EXECUTABLE_PATH not set
    if "PUPPETEER_EXECUTABLE_PATH" not in env:
        chromium_path = find_chromium()
        if chromium_path:
            env["PUPPETEER_EXECUTABLE_PATH"] = chromium_path
            print(f"  Using system browser: {chromium_path}")

    subprocess.run([
        "mmdc",
        "-i", str(input_file),
        "-o", str(output_file),
        "-b", "transparent",
        "-w", "1200"
    ], check=True, env=env)

    print(f"  Created: {output_file}")


def find_pdf_engine() -> tuple[str, list[str]]:
    """Find the best available PDF engine and return (engine, extra_args)."""
    # Prefer weasyprint - no LaTeX dependencies
    if check_command("weasyprint"):
        return "weasyprint", []

    # Fall back to xelatex if available
    if check_command("xelatex"):
        return "xelatex", [
            "-V", "geometry:margin=1in",
            "-V", "colorlinks=true",
            "-V", "linkcolor=blue",
        ]

    # Fall back to pdflatex
    if check_command("pdflatex"):
        return "pdflatex", [
            "-V", "geometry:margin=1in",
            "-V", "colorlinks=true",
            "-V", "linkcolor=blue",
        ]

    return "", []


def render_pdf(markdown_file: Path, output_file: Path) -> None:
    """Render markdown to PDF using pandoc."""
    engine, extra_args = find_pdf_engine()

    if not engine:
        print("Error: No PDF engine found.")
        print("Install one of:")
        print("  weasyprint  (recommended - no LaTeX needed)")
        print("  xelatex")
        print("  pdflatex")
        raise RuntimeError("No PDF engine available")

    print(f"  Using PDF engine: {engine}")

    cmd = [
        "pandoc", markdown_file.name,
        "-o", output_file.name,
        f"--pdf-engine={engine}",
        "--highlight-style=tango"
    ] + extra_args

    # Run from the docs directory so relative image paths resolve correctly
    subprocess.run(cmd, check=True, cwd=markdown_file.parent)

    print(f"  Created: {output_file}")


def main() -> int:
    project_root = Path(__file__).parent.parent
    docs_dir = project_root / "docs"
    diagrams_dir = docs_dir / "assets" / "diagrams"
    imgs_dir = docs_dir / "assets" / "imgs"

    # Check dependencies
    if not check_command("mmdc"):
        print("Error: mermaid-cli (mmdc) not found.")
        print("Install with: npm install -g @mermaid-js/mermaid-cli")
        return 1

    if not check_command("pandoc"):
        print("Error: pandoc not found.")
        print("Install with your package manager (e.g., dnf install pandoc)")
        return 1

    # Render all Mermaid diagrams
    mmd_files = sorted(diagrams_dir.glob("*.mmd"))
    if not mmd_files:
        print("No Mermaid diagrams found in", diagrams_dir)
    else:
        print(f"Rendering {len(mmd_files)} Mermaid diagram(s) to PNG...")
        for mmd_file in mmd_files:
            png_file = imgs_dir / mmd_file.with_suffix(".png").name
            render_mermaid(mmd_file, png_file)

    # Generate PDF
    print("Generating PDF with pandoc...")
    render_pdf(
        docs_dir / "index.md",
        docs_dir / "index.pdf"
    )

    print("Done!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
