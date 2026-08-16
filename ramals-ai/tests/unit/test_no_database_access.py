"""M1-T01: prove the AI plane has no database or migration capability.

The architectural rule — Python never reaches `core.*` or `ledger.*`, and never runs DDL against the
shared database — is enforced at the privilege layer by `V015` and proven by
`AiRuntimeBoundaryIntegrationTests` on the Java side. That stops a running service.

These tests stop it earlier and for a different reason: a dependency that *could* open a connection
is a dependency someone can use. Catching it in review depends on someone noticing a line in
`pyproject.toml`; catching it here does not.
"""

from __future__ import annotations

import importlib.util
import tomllib
from pathlib import Path

import pytest

# Drivers and ORMs that can open a connection to the shared PostgreSQL database.
FORBIDDEN_DATABASE_PACKAGES = ("psycopg", "psycopg2", "asyncpg", "sqlalchemy", "sqlmodel", "aiopg")

# Migration tools. Flyway under ramals_core_migration is the sole DDL authority for the shared
# database (Doc 08 §2); a second migration chain from Python would fork schema ownership.
FORBIDDEN_MIGRATION_PACKAGES = ("alembic", "yoyo", "django")


def _declared_dependencies() -> list[str]:
    manifest = tomllib.loads((Path(__file__).parents[2] / "pyproject.toml").read_text())
    declared: list[str] = list(manifest["project"]["dependencies"])
    for group in manifest["project"].get("optional-dependencies", {}).values():
        declared.extend(group)
    return [requirement.lower() for requirement in declared]


@pytest.mark.parametrize("package", FORBIDDEN_DATABASE_PACKAGES)
def test_no_database_driver_is_declared(package: str) -> None:
    assert not any(requirement.startswith(package) for requirement in _declared_dependencies()), (
        f"{package} would let this service open a connection to the authoritative database; "
        "the AI plane reads learner context through the platform API instead"
    )


@pytest.mark.parametrize("package", FORBIDDEN_MIGRATION_PACKAGES)
def test_no_migration_tool_is_declared(package: str) -> None:
    assert not any(requirement.startswith(package) for requirement in _declared_dependencies()), (
        f"{package} would create a second migration authority for the shared database; "
        "Flyway under ramals_core_migration owns all DDL"
    )


@pytest.mark.parametrize("package", FORBIDDEN_DATABASE_PACKAGES + FORBIDDEN_MIGRATION_PACKAGES)
def test_forbidden_package_is_not_importable(package: str) -> None:
    """Declared or not, it must not be present in the runtime environment."""
    assert importlib.util.find_spec(package) is None, (
        f"{package} is importable in this environment; a transitive dependency has reintroduced "
        "database or migration capability into the AI plane"
    )


def test_startup_performs_no_schema_work() -> None:
    """The application module must not reference DDL or migration verbs anywhere."""
    source_root = Path(__file__).parents[2] / "src"
    ddl_markers = ("CREATE TABLE", "ALTER TABLE", "DROP TABLE", "GRANT ", "REVOKE ")

    offenders: list[str] = []
    for module in source_root.rglob("*.py"):
        text = module.read_text(encoding="utf-8").upper()
        offenders.extend(
            f"{module.name}: {marker.strip()}" for marker in ddl_markers if marker in text
        )

    assert not offenders, f"AI plane source contains schema statements: {offenders}"
