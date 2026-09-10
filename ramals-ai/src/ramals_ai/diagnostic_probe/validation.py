"""Local validation of an MVP-2 diagnostic-probe proposal (M2-ADR-032 step 3).

This is the *early safety layer*, not the authority. Java's ``DiagnosticProbeProposalGate`` is
deterministic, versioned and fail-closed, and it re-validates every reference against the exact
``M_allowed`` / ``E_allowed`` sets it exposed -- it would reject a fabricated id, a forbidden
rationale, or an unauthorised candidate probe whether or not this module existed. Validating here as
well turns a wasted round trip into a bounded repair attempt and keeps an indefensible payload from
ever leaving this process (defence in depth).

Deliberately a separate module from ``diagnostic.validation`` and
``diagnostic_assessment.validation``: this contract forbids a verdict *and* forbids a confidence,
and mixing its rules with the assessment contract's (which requires one) would be one rule set
answering incompatible questions.
"""

from __future__ import annotations

import json
import re
from typing import Any

from pydantic import ValidationError

from ramals_ai.diagnostic_probe.contracts import DiagnosticProbeProposal

SCHEMA_NOT_JSON = "SCHEMA_NOT_JSON"
SCHEMA_NOT_OBJECT = "SCHEMA_NOT_OBJECT"
EVIDENCE_NOT_IN_CONTEXT = "EVIDENCE_NOT_IN_CONTEXT"
MISCONCEPTION_NOT_IN_CONTEXT = "MISCONCEPTION_NOT_IN_CONTEXT"
RATIONALE_FORBIDDEN_TERMINOLOGY = "RATIONALE_FORBIDDEN_TERMINOLOGY"

MAX_REPORTED_ERRORS = 16

#: Keys the model must never emit on this proposal type (M2-ADR-032 3). ``extra="forbid"`` on the
#: contract already rejects every unknown key; naming these explicitly gives the repair loop and an
#: evaluation harness a stable, specific code instead of a generic "extra field".
_FORBIDDEN_KEYS: frozenset[str] = frozenset(
    {
        "confidence",
        "probability",
        "likelihood",
        "posterior",
        "score",
        "rank",
        "ranking",
        "diagnosis",
        "rootCause",
        "root_cause",
        "masteryEstimate",
        "mastery_estimate",
        "selectedProbe",
        "selected_probe",
        "executionCommand",
        "execution_command",
        "eligibility",
        "eligibilityDecision",
    }
)

# Probability / comparative-likelihood / resolution language. Mirrors the lexical rule the Java gate
# applies to the rationale (M2-ADR-032 9; M2-ADR-030 G), so a rationale this layer passes is one the
# gate will not reject on terminology.
_FORBIDDEN_TERMINOLOGY = re.compile(
    r"\b("
    r"probabilit(?:y|ies)"
    r"|percentage|percent|per cent"
    r"|(?:more|less|most|least|equally) likely"
    r"|likelihood"
    r"|VERIFIED|CONFIRMED|RESOLVED|CURED|RECURRENCE|REGRESSION|REVERSAL|ROOT_CAUSE"
    r")\b",
    re.IGNORECASE,
)

_RUNTIME_OWNED = frozenset(
    {
        "contractVersion",
        "proposalType",
        "proposalId",
        "requestId",
        "agentRunId",
        "interactionId",
        "domain",
    }
)


def validate(
    raw: str,
    permitted_evidence_ids: frozenset[str],
    permitted_misconception_ids: frozenset[str],
) -> list[str]:
    """Returns validation error codes; an empty list means the proposal is worth sending on.

    Codes rather than prose, so the graph can branch on them and an evaluation harness can count
    them without parsing English.

    ``permitted_evidence_ids`` and ``permitted_misconception_ids`` are the sets the reasoner derived
    from its own bounded H6/H7 MCP reads -- the same governed evidence the model was shown. Passing
    a wider set than was supplied would defeat the check, which is why the reasoner derives both
    from the reports it built the prompt from, never from a caller-chosen parameter.
    """
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError, TypeError:
        return [SCHEMA_NOT_JSON]

    if not isinstance(parsed, dict):
        return [SCHEMA_NOT_OBJECT]

    forbidden_present = sorted(_FORBIDDEN_KEYS & set(parsed))
    if forbidden_present:
        return [f"FORBIDDEN_FIELD_{_screaming(key)}" for key in forbidden_present][
            :MAX_REPORTED_ERRORS
        ]

    try:
        proposal = DiagnosticProbeProposal.model_validate(_with_identity_placeholders(parsed))
    except ValidationError as invalid:
        return _schema_codes(invalid)

    return _grounding_errors(proposal, permitted_evidence_ids, permitted_misconception_ids)


def _with_identity_placeholders(parsed: dict[str, Any]) -> dict[str, Any]:
    """Supplies the identity fields the runtime owns, so the model is never asked for them.

    ``proposalId``/``requestId``/``agentRunId``/``interactionId``/``domain`` are required by the
    contract but assigned by the runtime -- a model-supplied one would be a correlation identity
    invented by the thing being correlated, and Java re-derives and re-checks them anyway. Filled
    with placeholders for validation and replaced with the real run values when the proposal is
    assembled.
    """
    return {
        "proposalId": "pending",
        "requestId": "pending",
        "agentRunId": "pending",
        "interactionId": "pending",
        "domain": "PENDING",
        **{key: value for key, value in parsed.items() if key not in _RUNTIME_OWNED},
    }


def _schema_codes(invalid: ValidationError) -> list[str]:
    """One stable code per offending field, rather than pydantic's prose."""
    codes: list[str] = []
    for error in invalid.errors():
        location = [part for part in error["loc"] if isinstance(part, str)]
        field = location[0] if location else "PROPOSAL"
        code = f"SCHEMA_INVALID_{_screaming(field)}"
        if code not in codes:
            codes.append(code)
    return codes[:MAX_REPORTED_ERRORS]


def _grounding_errors(
    proposal: DiagnosticProbeProposal,
    permitted_evidence_ids: frozenset[str],
    permitted_misconception_ids: frozenset[str],
) -> list[str]:
    """The checks this module exists for: no reference outside the bounded context, no verdict
    language in the rationale.

    Compared case-insensitively: Java emits lowercase UUIDs and this layer must never be *looser*
    than the gate, but a case-variant that the gate would still resolve should become a repair hint
    here rather than a hard mismatch the model cannot understand.
    """
    codes: list[str] = []

    permitted_evidence = {value.casefold() for value in permitted_evidence_ids}
    if not {ref.casefold() for ref in proposal.evidenceRefs} <= permitted_evidence:
        codes.append(EVIDENCE_NOT_IN_CONTEXT)

    permitted_misconceptions = {value.casefold() for value in permitted_misconception_ids}
    if proposal.targetMisconceptionId.casefold() not in permitted_misconceptions:
        codes.append(MISCONCEPTION_NOT_IN_CONTEXT)

    if _FORBIDDEN_TERMINOLOGY.search(proposal.rationale):
        codes.append(RATIONALE_FORBIDDEN_TERMINOLOGY)

    return codes


def _screaming(field: str) -> str:
    """``targetMisconceptionId`` -> ``TARGETMISCONCEPTIONID``, bounded so a code always fits."""
    return "".join(character for character in field if character.isalnum()).upper()[:40]
