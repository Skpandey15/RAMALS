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

# The only modules allowed to import any of these, and what each one is for.
#
# Two adapters rather than one since Contract B: the LiteLLM adapter serves Contract A's
# synchronous path, and the Anthropic Batches adapter serves durable recoverable execution, which
# LiteLLM cannot (M2-ADR-016 Addendum A). The rule itself is unchanged -- no agent, node or service
# module may import a provider, so every call still passes through the gateway where it can be
# priced, deadlined or refused. A second adapter *behind* that boundary does not weaken it; the
# thing this test exists to prevent is a provider import escaping into the service.
ADAPTERS = frozenset(
    {
        SOURCE_ROOT / "gateway" / "providers" / "litellm_adapter.py",
        SOURCE_ROOT / "gateway" / "providers" / "anthropic_batches_adapter.py",
    }
)

# Each adapter and the provider root it is exempted for. Pairing them rather than allowing any
# adapter to import anything keeps the exemption specific: the Contract A adapter importing the
# Anthropic SDK would be a real change and should fail here.
ADAPTER_IMPORTS = {
    SOURCE_ROOT / "gateway" / "providers" / "litellm_adapter.py": "litellm",
    SOURCE_ROOT / "gateway" / "providers" / "anthropic_batches_adapter.py": "anthropic",
}

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
    return sorted(p for p in SOURCE_ROOT.rglob("*.py") if p not in ADAPTERS)


@pytest.mark.parametrize("module", _service_modules(), ids=lambda p: str(p.name))
def test_no_module_outside_the_adapter_imports_a_provider_sdk(module: Path) -> None:
    leaked = _imported_roots(module) & PROVIDER_PACKAGES
    allowed = sorted(str(a.relative_to(SOURCE_ROOT)) for a in ADAPTERS)
    assert not leaked, (
        f"{module.relative_to(SOURCE_ROOT)} imports {sorted(leaked)}. Only {allowed} may; "
        "route it through LLMGateway instead."
    )


def test_the_scan_actually_covers_the_service() -> None:
    """A scan over an empty file list passes vacuously and proves nothing.

    This is the assertion that would have caught the MVP-0 realm guards, which were green for weeks
    because their input was never read.
    """
    modules = _service_modules()
    assert len(modules) > 10, f"only {len(modules)} modules scanned; the glob is probably wrong"
    for adapter in ADAPTERS:
        assert adapter.exists(), f"the adapter the exemption names must exist: {adapter}"


def test_the_scan_finds_the_one_import_that_is_allowed() -> None:
    """The adapter is the positive control: the scanner must see the import it exempts.

    Without this the suite could pass because the detector finds nothing anywhere -- a scan that
    detects no violations and no permitted uses is indistinguishable from a broken scan. The
    adapter's import is inside a method, so this also pins that nesting does not hide it.
    """
    for adapter, provider in ADAPTER_IMPORTS.items():
        assert provider in _imported_roots(adapter), (
            f"the scanner no longer sees {adapter.name}'s '{provider}' import; if it cannot find "
            "the imports that are allowed, it cannot find the ones that are not"
        )


def test_each_adapter_imports_only_the_provider_it_is_exempted_for() -> None:
    """The exemption is per adapter, not a blanket licence for the providers package.

    Without this, adding a second adapter would have widened the allowlist into "anything under
    providers/ may import anything" -- which is how a boundary that still passes its tests stops
    meaning what it says.
    """
    for adapter, provider in ADAPTER_IMPORTS.items():
        unexpected = _imported_roots(adapter) & PROVIDER_PACKAGES - {provider}
        assert not unexpected, (
            f"{adapter.name} imports {sorted(unexpected)}, but is exempted only for '{provider}'"
        )
