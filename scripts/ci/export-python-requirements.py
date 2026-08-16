#!/usr/bin/env python3
"""Export ramals-ai's declared dependencies as a requirements file.

`pyproject.toml` stays the single source of the pins; this projects them into the format
`pip-audit` and SBOM tooling expect, so neither is maintained by hand.

Auditing the *declared dependencies* rather than the installed environment matters: the environment
also contains `ramals-ai` itself, which is not on PyPI and never will be. `pip-audit --strict` fails
on anything it cannot audit, so pointing it at the environment makes the local package look like an
audit gap. Pointing it at this file keeps strict mode meaningful — a real dependency that cannot be
audited still fails the build.

    python scripts/ci/export-python-requirements.py ramals-ai/pyproject.toml requirements.txt
"""

from __future__ import annotations

import sys
import tomllib
from pathlib import Path


def declared_requirements(pyproject: Path) -> list[str]:
    manifest = tomllib.loads(pyproject.read_text(encoding="utf-8"))
    project = manifest["project"]

    requirements: list[str] = list(project.get("dependencies", []))
    for group in project.get("optional-dependencies", {}).values():
        requirements.extend(group)

    unpinned = [r for r in requirements if "==" not in r]
    if unpinned:
        raise SystemExit(
            f"{pyproject}: every dependency must be pinned with '=='; unpinned: {unpinned}"
        )

    return sorted(set(requirements))


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2

    pyproject, destination = Path(argv[1]), Path(argv[2])
    requirements = declared_requirements(pyproject)
    destination.write_text("\n".join(requirements) + "\n", encoding="utf-8")
    print(f"Exported {len(requirements)} pinned requirement(s) to {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
