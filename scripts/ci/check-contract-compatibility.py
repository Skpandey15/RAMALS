#!/usr/bin/env python3
"""Detect breaking changes to the internal AI contract.

The contract is an agreement between two independently deployed services. During a rolling release
one side runs the new contract while the other still runs the old one, so a change that is
"obviously fine" in a single codebase — renaming a field, making an optional field required,
removing an enum value — breaks a live boundary.

Compares `contracts/ai-internal.openapi.yaml` against the frozen baseline for its major version.
A breaking change requires a new major version and a new baseline, not an edit to this one.

    python scripts/ci/check-contract-compatibility.py

Additive changes pass: new optional fields, new endpoints, new enum values, relaxed limits.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
CURRENT = REPO_ROOT / "contracts" / "ai-internal.openapi.yaml"
BASELINE = REPO_ROOT / "contracts" / "baseline" / "ai-internal.openapi.v1.yaml"


def load(path: Path) -> dict[str, Any]:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def schemas(document: dict[str, Any]) -> dict[str, Any]:
    return document.get("components", {}).get("schemas", {})


def check(baseline: dict[str, Any], current: dict[str, Any]) -> list[str]:
    breaks: list[str] = []

    # An endpoint the peer still calls must keep existing.
    for path, operations in baseline.get("paths", {}).items():
        if path not in current.get("paths", {}):
            breaks.append(f"path removed: {path}")
            continue
        for method in operations:
            if method not in current["paths"][path]:
                breaks.append(f"operation removed: {method.upper()} {path}")

    base_schemas, current_schemas = schemas(baseline), schemas(current)

    for name, base in base_schemas.items():
        if name not in current_schemas:
            breaks.append(f"schema removed: {name}")
            continue
        now = current_schemas[name]

        # A field the peer still sends must keep being accepted.
        base_properties = set(base.get("properties", {}))
        current_properties = set(now.get("properties", {}))
        for removed in sorted(base_properties - current_properties):
            breaks.append(f"property removed: {name}.{removed}")

        # An optional field becoming required rejects payloads the peer already sends.
        newly_required = set(now.get("required", [])) - set(base.get("required", []))
        for field in sorted(newly_required):
            breaks.append(f"property became required: {name}.{field}")

        # Removing an enum value rejects a value the peer may still emit.
        base_enum, current_enum = set(base.get("enum", [])), set(now.get("enum", []))
        for value in sorted(base_enum - current_enum):
            breaks.append(f"enum value removed: {name} '{value}'")

        for field, base_property in base.get("properties", {}).items():
            current_property = now.get("properties", {}).get(field)
            if not isinstance(base_property, dict) or not isinstance(current_property, dict):
                continue

            base_values = set(base_property.get("enum", []))
            current_values = set(current_property.get("enum", []))
            for value in sorted(base_values - current_values):
                breaks.append(f"enum value removed: {name}.{field} '{value}'")

            # Tightening a bound rejects payloads that were previously legal.
            for constraint, tighter in (
                ("maxLength", lambda a, b: b < a),
                ("maxItems", lambda a, b: b < a),
                ("maximum", lambda a, b: b < a),
                ("minLength", lambda a, b: b > a),
                ("minimum", lambda a, b: b > a),
            ):
                before, after = base_property.get(constraint), current_property.get(constraint)
                if before is not None and after is not None and tighter(before, after):
                    breaks.append(
                        f"constraint tightened: {name}.{field} {constraint} {before} -> {after}"
                    )
            if base_property.get("type") != current_property.get("type"):
                breaks.append(
                    f"type changed: {name}.{field} "
                    f"{base_property.get('type')} -> {current_property.get('type')}"
                )

    return breaks


def main() -> int:
    if not BASELINE.exists():
        print(f"::error::missing contract baseline: {BASELINE.relative_to(REPO_ROOT)}", file=sys.stderr)
        return 1

    baseline, current = load(BASELINE), load(CURRENT)

    base_major = str(baseline["info"]["version"]).split(".")[0]
    current_major = str(current["info"]["version"]).split(".")[0]
    if base_major != current_major:
        print(
            f"Contract major version moved {base_major} -> {current_major}; "
            "freeze a new baseline for it and compare against that."
        )
        return 0

    breaks = check(baseline, current)
    if breaks:
        print(
            f"::error::{len(breaks)} breaking change(s) against contract v{base_major} baseline. "
            "Ship a new major version rather than editing v"
            f"{base_major}:",
            file=sys.stderr,
        )
        for entry in breaks:
            print(f"  - {entry}", file=sys.stderr)
        return 1

    print(f"Contract is backward compatible with the v{base_major} baseline.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
