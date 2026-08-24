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
import json
import logging
import os
import time

from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.providers.base import Message, ProviderRequest, ProviderResponse
from ramals_ai.telemetry.correlation import current_proposal_id

# Roughly four characters per token. Crude, and honest about it: the fake exists to be predictable,
# not to model a tokenizer. Tests that care about the input ceiling set the count they need.
_CHARS_PER_TOKEN = 4
_LOGGER = logging.getLogger(__name__)


class FakeProvider:
    """Deterministic canned completions. Costs nothing, calls nothing, never varies."""

    name = "ci-fake"

    def __init__(
        self,
        *,
        fail_with: GatewayErrorCode | None = None,
        qualification_fixtures: bool = False,
        qualification_provider_pause_enabled: bool = False,
        qualification_provider_pause_request_id: str | None = None,
        qualification_provider_pause_ms: int = 0,
    ) -> None:
        # Lets a test drive the gateway's failure handling through the same adapter surface a real
        # provider uses, instead of monkeypatching the gateway's internals.
        self._fail_with = fail_with
        self._qualification_fixtures = qualification_fixtures
        self._qualification_provider_pause_enabled = qualification_provider_pause_enabled
        self._qualification_provider_pause_request_id = qualification_provider_pause_request_id
        self._qualification_provider_pause_ms = qualification_provider_pause_ms

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

        self._pause_for_qualification()
        digest = self._digest(request)
        text = self._qualification_text(request)
        if text is None:
            text = f"[ci-fake:{request.model}] deterministic completion {digest[:16]}"
        return ProviderResponse(
            text=text,
            input_tokens=self.count_input_tokens(request.model, request.messages),
            # Bounded by the ceiling the gateway resolved, so a fake response can never appear to
            # have exceeded a budget the real path would have enforced.
            output_tokens=min(len(text) // _CHARS_PER_TOKEN, request.max_output_tokens),
            cached_input_tokens=0,
        )

    def _qualification_text(self, request: ProviderRequest) -> str | None:
        """Return valid workflow fixtures only when the isolated qualification mode is enabled."""
        if not self._qualification_fixtures:
            return None
        system = next(
            (message.content for message in request.messages if message.role == "system"), ""
        )
        if '"diagnoses"' in system or "recommendedNextSkills" in system:
            context = self._user_context(request.messages)
            skill_code = self._diagnostic_skill(context)
            evidence_id = self._diagnostic_evidence(context)
            if skill_code and evidence_id:
                return json.dumps(
                    {
                        "diagnoses": [
                            {
                                "skillCode": skill_code,
                                "classification": "WEAK",
                                "reason": (
                                    "The supplied learner evidence is below the expected level."
                                ),
                                "evidenceIds": [evidence_id],
                            }
                        ],
                        "recommendedNextSkills": [],
                        "confidence": 0.75,
                    },
                    separators=(",", ":"),
                )
        if '"recommendedAction"' in system or "next learning action" in system:
            context = self._user_context(request.messages)
            recommended_skill_code = context.get("skillCode")
            if isinstance(recommended_skill_code, str) and recommended_skill_code:
                return json.dumps(
                    {
                        "skillCode": recommended_skill_code,
                        "recommendedAction": "PRACTICE",
                        "rationale": (
                            "Focused practice reinforces the deterministic learning decision."
                        ),
                    },
                    separators=(",", ":"),
                )
        return None

    def _pause_for_qualification(self) -> None:
        """Hold the real provider adapter at a targeted diagnostic call boundary."""
        if not self._qualification_fixtures or not self._qualification_provider_pause_enabled:
            return
        request_id = current_proposal_id()
        target = self._qualification_provider_pause_request_id
        if target and target != request_id:
            return
        pause_ms = max(1, min(600_000, self._qualification_provider_pause_ms or 120_000))
        _LOGGER.warning(
            "qualification provider boundary reached; delete this pod now",
            extra={
                "operation": "qualification.fault",
                "window": "DIAGNOSTIC_PROVIDER_EXECUTION",
                "requestId": request_id,
                "pid": os.getpid(),
                "pauseMs": pause_ms,
            },
        )
        time.sleep(pause_ms / 1000)

    @staticmethod
    def _user_context(messages: tuple[Message, ...]) -> dict[str, object]:
        user = next((message.content for message in messages if message.role == "user"), "")
        try:
            payload = user.split(":\n", 1)[1]
            decoded = json.loads(payload)
            return decoded if isinstance(decoded, dict) else {}
        except IndexError, json.JSONDecodeError:
            return {}

    @staticmethod
    def _diagnostic_skill(context: dict[str, object]) -> str | None:
        items = context.get("items")
        if not isinstance(items, list):
            return None
        for item in items:
            if not isinstance(item, dict):
                continue
            fact_type = str(item.get("factType", "")).upper()
            if fact_type.endswith("SKILL_CODE") and item.get("value"):
                return str(item["value"])
        return None

    @staticmethod
    def _diagnostic_evidence(context: dict[str, object]) -> str | None:
        items = context.get("items")
        if not isinstance(items, list):
            return None
        for item in items:
            if not isinstance(item, dict):
                continue
            if item.get("sourceType") == "LEARNER_EVIDENCE" and item.get("evidenceId"):
                return str(item["evidenceId"])
        return None

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
