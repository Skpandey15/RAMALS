#!/usr/bin/env python3
"""Re-checks an attestation produced on the system under test.

Once the load generator is on its own machine, ``run-baseline.sh`` no longer runs on the host it is
measuring, so it cannot attest that host itself. The system under test attests itself and the file
travels with the run.

A file that travels is a file that can be edited, kept past its usefulness, or copied from a
different machine -- so it is re-checked rather than believed:

* it must say the host conformed, because a failed attestation carried alongside a qualified id is
  a contradiction rather than evidence;
* it must be the spec the run claims, since an attestation against some other environment describes
  a different set of requirements;
* it must be recent, because a host that conformed last month may have been resized since, and an
  attestation has no way to notice that on its own.

None of this makes the file trustworthy against someone determined to forge it. It makes the honest
mistakes -- a stale file, the wrong file, a file from the wrong host -- visible, which is what
actually happens.

Usage:
    python verify-attestation.py <file> --expect-spec <id> [--max-age-hours N]
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path


def problems(
    attestation: dict[str, object], expected_spec: str, max_age: timedelta
) -> list[str]:
    """Every reason this attestation cannot stand behind a qualified run."""
    found: list[str] = []

    if attestation.get("specId") != expected_spec:
        found.append(
            f"attests '{attestation.get('specId')}' but the run claims '{expected_spec}'"
        )

    if attestation.get("conforms") is not True:
        failures = attestation.get("failures") or []
        detail = f": {'; '.join(str(f) for f in failures)}" if failures else ""
        found.append(f"records that the host did NOT conform{detail}")

    stamped = attestation.get("attestedAt")
    if not isinstance(stamped, str):
        found.append("has no attestedAt, so its age cannot be judged")
    else:
        try:
            when = datetime.fromisoformat(stamped)
        except ValueError:
            found.append(f"has an unreadable attestedAt: {stamped!r}")
        else:
            if when.tzinfo is None:
                when = when.replace(tzinfo=UTC)
            age = datetime.now(UTC) - when
            if age > max_age:
                found.append(
                    f"is {age.total_seconds() / 3600:.1f} hours old, older than the "
                    f"{max_age.total_seconds() / 3600:.0f}-hour limit; the host may have changed"
                )

    if not isinstance(attestation.get("measured"), dict):
        found.append("carries no measurements, so there is nothing behind the verdict")

    return found


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("attestation", type=Path)
    parser.add_argument("--expect-spec", required=True)
    parser.add_argument("--max-age-hours", type=float, default=24.0)
    arguments = parser.parse_args(argv[1:])

    if not arguments.attestation.is_file():
        print(f"no attestation at {arguments.attestation}", file=sys.stderr)
        return 1

    try:
        attestation = json.loads(arguments.attestation.read_text(encoding="utf-8"))
    except json.JSONDecodeError as broken:
        print(f"attestation is not readable JSON: {broken}", file=sys.stderr)
        return 1

    found = problems(
        attestation, arguments.expect_spec, timedelta(hours=arguments.max_age_hours)
    )
    if found:
        print(f"The supplied attestation cannot stand behind a '{arguments.expect_spec}' run:\n")
        for problem in found:
            print(f"  - it {problem}")
        return 1

    host = attestation.get("measured", {}).get("host", {})
    print(
        f"Attestation accepted: {attestation['specId']} on "
        f"{host.get('runtime_host_name', 'an unnamed host')} "
        f"({host.get('cpus')} CPUs, {host.get('memory_gib')} GiB), "
        f"attested {attestation['attestedAt']}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
