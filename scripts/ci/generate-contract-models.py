#!/usr/bin/env python3
"""Generate the Python contract models from the canonical OpenAPI document.

M1-ADR-002: `contracts/ai-internal.openapi.yaml` is the normative source. Python models are
generated from it and committed; Java records are hand-written and validated against the same
contract by the golden fixtures.

Generation must be deterministic, or the CI drift check becomes a coin toss. That means an explicit
formatter set and no embedded timestamp.

    python scripts/ci/generate-contract-models.py            # regenerate in place
    python scripts/ci/generate-contract-models.py --check    # fail if the committed output is stale
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CONTRACT = REPO_ROOT / "contracts" / "ai-internal.openapi.yaml"
OUTPUT = REPO_ROOT / "ramals-ai" / "src" / "ramals_ai" / "contracts" / "generated.py"

HEADER = """# GENERATED FROM contracts/ai-internal.openapi.yaml -- DO NOT EDIT BY HAND.
# Regenerate with: python scripts/ci/generate-contract-models.py
# M1-ADR-002: Python models are generated; Java records are hand-written and validated against the
# same contract by the golden fixtures in contracts/golden/."""


def generate(destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(  # noqa: S603 - fixed argument vector, no shell
        [
            sys.executable,
            "-m",
            "datamodel_code_generator",
            "--input", str(CONTRACT),
            "--input-file-type", "openapi",
            "--output", str(destination),
            "--output-model-type", "pydantic_v2.BaseModel",
            "--target-python-version", "3.13",
            "--use-standard-collections",
            "--use-union-operator",
            "--field-constraints",
            "--use-annotated",
            "--enum-field-as-literal", "one",
            "--disable-timestamp",
            "--formatters", "black",
            "--formatters", "isort",
            "--custom-file-header", HEADER,
        ],
        check=True,
        cwd=REPO_ROOT,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if the committed models differ from a fresh generation",
    )
    args = parser.parse_args()

    if not args.check:
        generate(OUTPUT)
        print(f"Generated {OUTPUT.relative_to(REPO_ROOT)}")
        return 0

    with tempfile.TemporaryDirectory() as workspace:
        fresh = Path(workspace) / "generated.py"
        generate(fresh)
        if not OUTPUT.exists():
            print(f"::error::{OUTPUT.relative_to(REPO_ROOT)} is missing; run the generator", file=sys.stderr)
            return 1
        if fresh.read_text(encoding="utf-8") != OUTPUT.read_text(encoding="utf-8"):
            print(
                "::error::Committed contract models are stale. The OpenAPI document changed without "
                "regenerating. Run: python scripts/ci/generate-contract-models.py",
                file=sys.stderr,
            )
            return 1

    print("Committed contract models match the contract.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
