"""The deterministic ``ci-fake`` provider.

A first-class route, not test scaffolding (M1-ADR-008). CI runs the whole gateway path through it,
so retries, budgets, metadata and error handling are exercised by the same code that will carry a
real provider -- rather than by a mock that agrees with whatever the test expects.

Determinism comes from hashing the request with SHA-256. Python's ``hash()`` is salted per process
and would make "deterministic" mean "within one run", which is exactly the property that fails to
hold on the machine you are trying to reproduce a result on.
"""

from __future__ import annotations

import hashlib

from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.providers.base import Message, ProviderRequest, ProviderResponse

# Roughly four characters per token. Crude, and honest about it: the fake exists to be predictable,
# not to model a tokenizer. Tests that care about the input ceiling set the count they need.
_CHARS_PER_TOKEN = 4


class FakeProvider:
    """Deterministic canned completions. Costs nothing, calls nothing, never varies."""

    name = "ci-fake"

    def __init__(self, *, fail_with: GatewayErrorCode | None = None) -> None:
        # Lets a test drive the gateway's failure handling through the same adapter surface a real
        # provider uses, instead of monkeypatching the gateway's internals.
        self._fail_with = fail_with

    def count_input_tokens(self, model: str, messages: tuple[Message, ...]) -> int:  # noqa: ARG002
        # `model` is part of the adapter protocol. A real tokenizer needs it; this estimate does
        # not, and dropping it from the signature would break protocol conformance.
        characters = sum(len(message.role) + len(message.content) for message in messages)
        return max(1, characters // _CHARS_PER_TOKEN)

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        if self._fail_with is not None:
            raise GatewayError(
                self._fail_with, f"ci-fake was configured to fail with {self._fail_with}"
            )

        digest = self._digest(request)
        text = f"[ci-fake:{request.model}] deterministic completion {digest[:16]}"
        return ProviderResponse(
            text=text,
            input_tokens=self.count_input_tokens(request.model, request.messages),
            # Bounded by the ceiling the gateway resolved, so a fake response can never appear to
            # have exceeded a budget the real path would have enforced.
            output_tokens=min(len(text) // _CHARS_PER_TOKEN, request.max_output_tokens),
            cached_input_tokens=0,
        )

    @staticmethod
    def _digest(request: ProviderRequest) -> str:
        """Stable across processes, machines and runs.

        Excludes ``timeout_seconds``: it is derived from the caller's remaining deadline and so
        differs on every call. Including it would make identical requests produce different output,
        which is the one thing this provider exists to rule out.
        """
        material = "\x1f".join(
            [
                request.model,
                str(request.max_output_tokens),
                *(f"{message.role}\x1e{message.content}" for message in request.messages),
            ]
        )
        return hashlib.sha256(material.encode("utf-8")).hexdigest()
