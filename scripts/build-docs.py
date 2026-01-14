#!/usr/bin/env python3
"""
Build documentation for GitHub Pages.

Generates:
1. HTML with client-side Mermaid.js rendering
2. PDF with pre-rendered PNG diagrams (for download)

Usage:
    python scripts/build-docs.py [--html-only] [--pdf-only]

Requirements:
    - pandoc
    - weasyprint (pip install weasyprint) - for PDF generation
    - mermaid-cli (npm install -g @mermaid-js/mermaid-cli) - for PNG rendering
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

# GitHub repository base URL for source links
GITHUB_REPO_URL = "https://github.com/tomncooper/realtime-context-demo/blob/main"


def check_command(cmd: str) -> bool:
    """Check if a command is available."""
    return shutil.which(cmd) is not None


def find_chromium() -> str | None:
    """Find system Chromium/Chrome executable for mermaid-cli."""
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


def preprocess_for_html(markdown_content: str, docs_dir: Path) -> str:
    """
    Replace PNG diagram references with inline Mermaid code blocks.

    Transforms:
        ![Alt text](assets/imgs/diagram.png)
    Into:
        <figure>
        <pre class="mermaid">
        ... mermaid code ...
        </pre>
        <figcaption>Alt text</figcaption>
        </figure>
    """
    # Pattern matches: ![Alt text](assets/imgs/something.png)
    pattern = r'!\[([^\]]*)\]\(assets/imgs/([^)]+)\.png\)'

    def replace_with_mermaid(match: re.Match) -> str:
        alt_text = match.group(1)
        diagram_name = match.group(2)
        mmd_file = docs_dir / "assets" / "diagrams" / f"{diagram_name}.mmd"

        if mmd_file.exists():
            mermaid_content = mmd_file.read_text().strip()
            # Return HTML figure with mermaid code block
            return f'''<figure>
<pre class="mermaid">
{mermaid_content}
</pre>
<figcaption>{alt_text}</figcaption>
</figure>'''
        else:
            # Keep original if no .mmd file exists (might be a regular image)
            print(f"  Warning: No .mmd file found for {diagram_name}, keeping PNG reference")
            return match.group(0)

    return re.sub(pattern, replace_with_mermaid, markdown_content)


def linkify_file_paths(markdown_content: str, project_root: Path) -> str:
    """
    Convert backtick-wrapped file paths to GitHub links.

    Transforms:
        `query-api/src/main/java/com/smartship/api/Example.java`
    Into:
        [`query-api/src/main/java/com/smartship/api/Example.java`](https://github.com/.../blob/main/...)

    Only converts paths that actually exist in the repository.
    """
    # Pattern matches backtick-wrapped file paths with extensions
    # Matches: `dir/subdir/file.ext` where path has at least one / and ends with .ext
    pattern = r'`([a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_.-]+)+\.[a-zA-Z0-9]+)`'

    def replace_with_link(match: re.Match) -> str:
        file_path = match.group(1)
        full_path = project_root / file_path

        # Only convert if file exists in repo
        if full_path.exists():
            github_url = f"{GITHUB_REPO_URL}/{file_path}"
            return f"[`{file_path}`]({github_url})"
        else:
            # Keep original if file doesn't exist
            return match.group(0)

    return re.sub(pattern, replace_with_link, markdown_content)


def find_pdf_engine() -> tuple[str, list[str]]:
    """Find the best available PDF engine and return (engine, extra_args)."""
    if check_command("weasyprint"):
        return "weasyprint", []
    if check_command("xelatex"):
        return "xelatex", [
            "-V", "geometry:margin=1in",
            "-V", "colorlinks=true",
            "-V", "linkcolor=blue",
        ]
    if check_command("pdflatex"):
        return "pdflatex", [
            "-V", "geometry:margin=1in",
            "-V", "colorlinks=true",
            "-V", "linkcolor=blue",
        ]
    return "", []


def render_mermaid_pngs(docs_dir: Path) -> None:
    """Render Mermaid diagrams to PNG for PDF generation."""
    diagrams_dir = docs_dir / "assets" / "diagrams"
    imgs_dir = docs_dir / "assets" / "imgs"
    imgs_dir.mkdir(parents=True, exist_ok=True)

    if not check_command("mmdc"):
        print("  Warning: mermaid-cli (mmdc) not found, skipping PNG rendering")
        print("  Install with: npm install -g @mermaid-js/mermaid-cli")
        return

    env = os.environ.copy()

    # Try to use system Chromium if PUPPETEER_EXECUTABLE_PATH not set
    if "PUPPETEER_EXECUTABLE_PATH" not in env:
        chromium_path = find_chromium()
        if chromium_path:
            env["PUPPETEER_EXECUTABLE_PATH"] = chromium_path
            print(f"  Using system browser: {chromium_path}")

    mmd_files = list(diagrams_dir.glob("*.mmd"))
    if not mmd_files:
        print("  No Mermaid diagrams found")
        return

    print(f"  Rendering {len(mmd_files)} Mermaid diagram(s) to PNG...")
    for mmd_file in mmd_files:
        png_file = imgs_dir / mmd_file.with_suffix(".png").name
        try:
            subprocess.run(
                [
                    "mmdc",
                    "-i", str(mmd_file),
                    "-o", str(png_file),
                    "-b", "white",
                    "-w", "1200",
                ],
                check=True,
                env=env,
                capture_output=True,
            )
            print(f"    {mmd_file.name} -> {png_file.name}")
        except subprocess.CalledProcessError as e:
            print(f"    Error rendering {mmd_file.name}: {e.stderr.decode()}")


def build_html(docs_dir: Path, output_dir: Path) -> None:
    """Build HTML with Mermaid.js client-side rendering."""
    print("\nBuilding HTML...")

    if not check_command("pandoc"):
        print("Error: pandoc not found")
        print("Install with your package manager (e.g., dnf install pandoc)")
        sys.exit(1)

    # Read and preprocess markdown
    index_md = docs_dir / "index.md"
    project_root = docs_dir.parent
    content = index_md.read_text()

    # Apply preprocessing transformations
    processed_content = preprocess_for_html(content, docs_dir)
    processed_content = linkify_file_paths(processed_content, project_root)

    # Write preprocessed markdown to temp file
    temp_md = output_dir / "_index_processed.md"
    temp_md.write_text(processed_content)

    # Run Pandoc with custom template
    template_path = docs_dir / "templates" / "smartship.html"
    output_html = output_dir / "index.html"

    cmd = [
        "pandoc", str(temp_md),
        "-o", str(output_html),
        "--standalone",
        "--template", str(template_path),
        "--toc",
        "--toc-depth=3",
        "--highlight-style=tango",
        "--metadata", "title=Real-time Context Demo",
    ]

    subprocess.run(cmd, check=True)

    # Cleanup temp file
    temp_md.unlink()

    # Copy CSS
    css_output = output_dir / "assets" / "css"
    css_output.mkdir(parents=True, exist_ok=True)
    shutil.copy(docs_dir / "css" / "style.css", css_output / "style.css")

    print(f"  Created: {output_html}")


def build_pdf(docs_dir: Path, output_dir: Path) -> None:
    """Build PDF using pre-rendered PNG diagrams."""
    print("\nBuilding PDF...")

    if not check_command("pandoc"):
        print("Error: pandoc not found")
        sys.exit(1)

    # Find PDF engine
    pdf_engine, extra_args = find_pdf_engine()

    if not pdf_engine:
        print("  Warning: No PDF engine found, skipping PDF generation")
        print("  Install weasyprint (pip install weasyprint) or a LaTeX distribution")
        return

    print(f"  Using PDF engine: {pdf_engine}")

    # Ensure PNGs are up to date
    render_mermaid_pngs(docs_dir)

    # Copy images to output directory so pandoc can find them
    imgs_src = docs_dir / "assets" / "imgs"
    imgs_dst = output_dir / "assets" / "imgs"
    if imgs_dst.exists():
        shutil.rmtree(imgs_dst)
    if imgs_src.exists():
        shutil.copytree(imgs_src, imgs_dst)

    output_pdf = output_dir / "index.pdf"

    # Build command
    cmd = [
        "pandoc", "index.md",
        "-o", str(output_pdf),
        f"--pdf-engine={pdf_engine}",
        "--highlight-style=tango",
        "--metadata", "title=Real-time Context Demo",
    ] + extra_args

    # Run from docs directory so relative image paths resolve
    subprocess.run(cmd, check=True, cwd=docs_dir)

    print(f"  Created: {output_pdf}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build documentation for GitHub Pages")
    parser.add_argument("--html-only", action="store_true", help="Only build HTML")
    parser.add_argument("--pdf-only", action="store_true", help="Only build PDF")
    args = parser.parse_args()

    project_root = Path(__file__).parent.parent
    docs_dir = project_root / "docs"
    output_dir = docs_dir / "_site"

    print("SmartShip Documentation Builder")
    print("=" * 40)

    # Clean and create output directory
    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)

    # Build outputs based on args
    build_html_flag = not args.pdf_only
    build_pdf_flag = not args.html_only

    if build_html_flag:
        build_html(docs_dir, output_dir)

    if build_pdf_flag:
        build_pdf(docs_dir, output_dir)

    print("\n" + "=" * 40)
    print(f"Done! Output in {output_dir}")
    print("\nTo preview locally:")
    print(f"  python -m http.server -d {output_dir} 8000")
    print("  Then open http://localhost:8000")

    return 0


if __name__ == "__main__":
    sys.exit(main())
