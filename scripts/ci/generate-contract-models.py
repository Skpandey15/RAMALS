#!/usr/bin/env python3
"""Generate the Python contract models from the canonical OpenAPI document.

M1-ADR-002: `contracts/ai-internal.openapi.yaml` is the normative source. Python models are
generated from it and committed; Java records are hand-written and validated against the same
contract by the golden fixtures.

Generation must be deterministic, or the CI drift check becomes a coin toss. It uses the generator's
dependency-free `builtin` formatter and no embedded timestamp.

The external black/isort integration is deliberately not used: with the same pinned versions it
formatted on Windows and did not on Linux, so the committed output only matched on the machine that
produced it. A drift check that depends on the developer's operating system is worse than none --
it fails for a reason that has nothing to do with the contract.

The check also generates into the *same directory* as the committed file. The formatter resolves its
line length from configuration near the output path, so writing the comparison copy to a temp
directory silently reformatted it at a different width and reported drift that did not exist.

    python scripts/ci/generate-contract-models.py            # regenerate in place
    python scripts/ci/generate-contract-models.py --check    # fail if the committed output is stale
"""

from __future__ import annotations

import argparse
import subprocess
import sys
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
            "--formatters", "builtin",
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

    if not OUTPUT.exists():
        print(
            f"::error::{OUTPUT.relative_to(REPO_ROOT)} is missing; run the generator",
            file=sys.stderr,
        )
        return 1

    fresh = OUTPUT.with_name(".generated-check.py")
    try:
        generate(fresh)
        if fresh.read_text(encoding="utf-8") != OUTPUT.read_text(encoding="utf-8"):
            print(
                "::error::Committed contract models are stale. The OpenAPI document changed without "
                "regenerating. Run: python scripts/ci/generate-contract-models.py",
                file=sys.stderr,
            )
            return 1
    finally:
        fresh.unlink(missing_ok=True)

    print("Committed contract models match the contract.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
