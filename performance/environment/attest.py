#!/usr/bin/env python3
"""Measures the host against a performance environment spec, and says so in writing.

R1 is open because a performance number measured on a developer workstation cannot be told apart
from a calibrated one. The environment id was a free-text variable copied into the baseline, so
``RAMALS_PERF_ENV=perf-standard-01`` on any machine produced a file that claimed to be
authoritative. Buying the right hardware does not fix that: the claim would still be unchecked.

This turns the id into something a run has to earn. It reads what is actually running -- host
capacity from the container runtime, CPU and memory limits from the containers themselves -- and
compares it against the spec. ``run-baseline.sh`` refuses to stamp a qualified id without a passing
attestation, so an unqualified run stays labelled ``local-unqualified`` however the operator set the
variable.

What it deliberately does not do is judge whether the numbers are *good*. It judges whether the
conditions were the declared ones. A slow run on a conforming host is a real result; a fast one on a
laptop is not a result at all.

No third-party dependencies: the runtime already knows everything needed, and a check that has to be
installed before it can run is a check that gets skipped.

Usage:
    python performance/environment/attest.py [--spec <file>] [--out <file>] [--require]

    --require   exit non-zero when the host does not conform. Without it the attestation is written
                and the verdict reported, which is what an informational run wants.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
DEFAULT_SPEC = HERE / "perf-standard-01.json"

BYTES_PER_GIB = 1024**3
NANO_CPUS = 1_000_000_000


class AttestationError(RuntimeError):
    """The host could not be inspected at all, which is not the same as failing to conform."""


def docker_info() -> dict[str, Any]:
    """Host capacity as the container runtime sees it.

    Taken from the runtime rather than from the operating system because the runtime's view is the
    one the system under test actually gets. On a VM-backed Docker -- Rancher Desktop, Docker
    Desktop, WSL -- the host may have far more CPU and memory than the containers can ever use, and
    attesting the outer machine would certify capacity nothing can reach.
    """
    try:
        completed = subprocess.run(
            ["docker", "info", "--format", "{{json .}}"],
            capture_output=True,
            text=True,
            check=True,
        )
    except FileNotFoundError as missing:
        raise AttestationError("docker is not on PATH, so the host cannot be attested") from missing
    except subprocess.CalledProcessError as failed:
        raise AttestationError(f"docker info failed: {failed.stderr.strip()}") from failed
    return json.loads(completed.stdout)


def container_limits(names: list[str]) -> dict[str, dict[str, Any]]:
    """The CPU and memory limits of the running services, read from the containers themselves.

    Not from the compose file. A compose file records an intention; a running container records
    what the kernel will enforce, and the two differ whenever somebody started the stack a different
    way -- which is exactly the situation an attestation exists to catch.
    """
    found: dict[str, dict[str, Any]] = {}
    for name in names:
        reference = name
        try:
            completed = subprocess.run(
                ["docker", "inspect", reference, "--format", "{{json .HostConfig}}"],
                capture_output=True,
                text=True,
                check=True,
            )
        except FileNotFoundError:
            found[name] = {"running": False}
            continue
        except subprocess.CalledProcessError:
            # Compose container names include the project prefix and replica suffix. Resolve the
            # service by its stable Compose label, but reject missing or ambiguous deployments.
            candidates = subprocess.run(
                [
                    "docker",
                    "ps",
                    "--filter",
                    f"label=com.docker.compose.service={name}",
                    "--filter",
                    "status=running",
                    "--format",
                    "{{.ID}}",
                ],
                capture_output=True,
                text=True,
                check=True,
            ).stdout.splitlines()
            if len(candidates) != 1:
                found[name] = {"running": False}
                continue
            reference = candidates[0]
            completed = subprocess.run(
                ["docker", "inspect", reference, "--format", "{{json .HostConfig}}"],
                capture_output=True,
                text=True,
                check=True,
            )
        config = json.loads(completed.stdout)
        nano = config.get("NanoCpus") or 0
        memory = config.get("Memory") or 0
        found[name] = {
            "running": True,
            "cpus": round(nano / NANO_CPUS, 3) if nano else None,
            "memory_gib": round(memory / BYTES_PER_GIB, 3) if memory else None,
        }
    return found


def measure(spec: dict[str, Any]) -> dict[str, Any]:
    """Everything the verdict is computed from, and everything a reader may later want."""
    info = docker_info()
    services = list(spec.get("containers", {}).keys())
    services = [name for name in services if not name.startswith("$")]

    return {
        "host": {
            "cpus": info.get("NCPU"),
            "memory_gib": round((info.get("MemTotal") or 0) / BYTES_PER_GIB, 2),
            "os_type": info.get("OSType"),
            "architecture": info.get("Architecture"),
            "operating_system": info.get("OperatingSystem"),
            "kernel_version": info.get("KernelVersion"),
            "docker_version": info.get("ServerVersion"),
            "runtime_host_name": info.get("Name"),
        },
        "containers": container_limits(services),
    }


def evaluate(spec: dict[str, Any], measured: dict[str, Any]) -> list[str]:
    """Every way this host falls short, not merely the first.

    All of them are reported because a reader deciding whether to provision a machine needs the
    whole gap, and stopping at the first failure turns that into a guessing game.
    """
    failures: list[str] = []
    required_host = spec.get("host", {})
    host = measured["host"]

    minimum_cpus = required_host.get("min_cpus")
    if minimum_cpus is not None and (host["cpus"] or 0) < minimum_cpus:
        failures.append(
            f"host exposes {host['cpus']} CPUs to the container runtime, spec requires "
            f"{minimum_cpus}"
        )

    minimum_memory = required_host.get("min_memory_gib")
    if minimum_memory is not None and host["memory_gib"] < minimum_memory:
        failures.append(
            f"host exposes {host['memory_gib']} GiB to the container runtime, spec requires "
            f"{minimum_memory}"
        )

    for field in ("os_type", "architecture"):
        expected = required_host.get(field)
        if expected is not None and host[field] != expected:
            failures.append(f"host {field} is {host[field]}, spec requires {expected}")

    for name, required in spec.get("containers", {}).items():
        if name.startswith("$"):
            continue
        actual = measured["containers"].get(name, {"running": False})
        if not actual.get("running"):
            failures.append(f"service '{name}' is not running, so its limits cannot be attested")
            continue
        if actual.get("cpus") is None:
            failures.append(
                f"service '{name}' has no CPU limit, so it inherits the whole host and the run is "
                "not comparable with one made elsewhere"
            )
        elif actual["cpus"] != required["cpus"]:
            failures.append(
                f"service '{name}' is limited to {actual['cpus']} CPUs, spec requires "
                f"{required['cpus']}"
            )
        if actual.get("memory_gib") is None:
            failures.append(f"service '{name}' has no memory limit, so the run is not comparable")
        elif abs(actual["memory_gib"] - required["memory_gib"]) > 0.05:
            failures.append(
                f"service '{name}' is limited to {actual['memory_gib']} GiB, spec requires "
                f"{required['memory_gib']}"
            )

    # The one requirement no measurement here can settle. k6 running on the host it measures takes
    # CPU from the system under test and folds its own scheduling into the reported latency, and
    # nothing visible from inside this process distinguishes that from a quiet host. It is asserted
    # by the operator and recorded as an assertion, so a reader knows which it was.
    if spec.get("isolation", {}).get("load_generator_off_host"):
        if not measured.get("load_generator_off_host"):
            failures.append(
                "the load generator must not run on the host under test; set "
                "RAMALS_PERF_LOAD_GENERATOR_OFF_HOST=true once it runs elsewhere, and expect the "
                "attestation to record that this was asserted rather than measured"
            )

    return failures


def attest(spec_path: Path, off_host: bool) -> dict[str, Any]:
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    measured = measure(spec)
    measured["load_generator_off_host"] = off_host
    failures = evaluate(spec, measured)

    return {
        "specId": spec["id"],
        "specVersion": spec.get("specVersion", "unknown"),
        "specStatus": spec.get("status", "unknown"),
        "attestedAt": datetime.now(UTC).isoformat(timespec="seconds"),
        "conforms": not failures,
        "failures": failures,
        "measured": measured,
        "$comment": (
            "load_generator_off_host is asserted by the operator, not measured. Every other field "
            "was read from the container runtime at the moment of the run."
        ),
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, default=DEFAULT_SPEC)
    parser.add_argument("--out", type=Path)
    parser.add_argument(
        "--require",
        action="store_true",
        help="exit non-zero unless the host conforms",
    )
    parser.add_argument(
        "--load-generator-off-host",
        action="store_true",
        help="assert that the load generator runs on a different machine",
    )
    arguments = parser.parse_args(argv[1:])

    if not arguments.spec.is_file():
        print(f"no environment spec at {arguments.spec}", file=sys.stderr)
        return 2

    try:
        attestation = attest(arguments.spec, arguments.load_generator_off_host)
    except AttestationError as failure:
        print(f"cannot attest this host: {failure}", file=sys.stderr)
        return 2

    if arguments.out:
        arguments.out.parent.mkdir(parents=True, exist_ok=True)
        arguments.out.write_text(json.dumps(attestation, indent=2) + "\n", encoding="utf-8")

    if attestation["conforms"]:
        print(f"Host conforms to {attestation['specId']} (spec version {attestation['specVersion']}).")
        if attestation["specStatus"] != "reference":
            print(
                f"  note: the spec is '{attestation['specStatus']}' -- its values are confirmed by "
                "the first calibrated run, not before it."
            )
        return 0

    print(f"Host does NOT conform to {attestation['specId']}:\n")
    for failure in attestation["failures"]:
        print(f"  - {failure}")
    print(
        "\nA run made here is informational. It stays labelled 'local-unqualified', which is the "
        "honest label for it, and no amount of setting RAMALS_PERF_ENV changes that."
    )
    return 1 if arguments.require else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
