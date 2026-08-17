"""No agent, node or service module may import a provider SDK.

M1-T05's first acceptance criterion. It is checked by scanning the source rather than by review,
for the same reason the MVP-0 engine freeze hashes behaviour instead of trusting a convention: a
boundary nothing verifies erodes at the first inconvenient deadline, and the erosion is invisible
in a diff that looks locally reasonable.

The cost of losing this boundary is not stylistic. Once three modules import a provider directly,
per-route budgets stop being enforceable -- there is no longer a single place where a call can be
priced, deadlined or refused.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

SOURCE_ROOT = Path(__file__).resolve().parents[2] / "src" / "ramals_ai"

# The only module allowed to import any of these.
ADAPTER = SOURCE_ROOT / "gateway" / "providers" / "litellm_adapter.py"

PROVIDER_PACKAGES = frozenset(
    {
        "litellm",
        "openai",
        "anthropic",
        "google",
        "google_generativeai",
        "cohere",
        "mistralai",
        "boto3",  # bedrock
        "vertexai",
        "transformers",
        "huggingface_hub",
        "ollama",
    }
)


def _imported_roots(path: Path) -> set[str]:
    """Top-level package names imported by a module, from the AST rather than by regex.

    A regex over source would count the word `litellm` in this docstring, in a comment explaining
    why the rule exists, and in the error message of the very test enforcing it.
    """
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    roots: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            roots.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            roots.add(node.module.split(".")[0])
    return roots


def _service_modules() -> list[Path]:
    return sorted(p for p in SOURCE_ROOT.rglob("*.py") if p != ADAPTER)


@pytest.mark.parametrize("module", _service_modules(), ids=lambda p: str(p.name))
def test_no_module_outside_the_adapter_imports_a_provider_sdk(module: Path) -> None:
    leaked = _imported_roots(module) & PROVIDER_PACKAGES
    assert not leaked, (
        f"{module.relative_to(SOURCE_ROOT)} imports {sorted(leaked)}. Only "
        f"{ADAPTER.relative_to(SOURCE_ROOT)} may; route it through LLMGateway instead."
    )


def test_the_scan_actually_covers_the_service() -> None:
    """A scan over an empty file list passes vacuously and proves nothing.

    This is the assertion that would have caught the MVP-0 realm guards, which were green for weeks
    because their input was never read.
    """
    modules = _service_modules()
    assert len(modules) > 10, f"only {len(modules)} modules scanned; the glob is probably wrong"
    assert ADAPTER.exists(), "the adapter the exemption names must exist"


def test_the_scan_finds_the_one_import_that_is_allowed() -> None:
    """The adapter is the positive control: the scanner must see the import it exempts.

    Without this the suite could pass because the detector finds nothing anywhere -- a scan that
    detects no violations and no permitted uses is indistinguishable from a broken scan. The
    adapter's import is inside a method, so this also pins that nesting does not hide it.
    """
    assert "litellm" in _imported_roots(ADAPTER), (
        "the scanner no longer sees the adapter's provider import; if it cannot find the one "
        "import that is allowed, it cannot find the ones that are not"
    )
