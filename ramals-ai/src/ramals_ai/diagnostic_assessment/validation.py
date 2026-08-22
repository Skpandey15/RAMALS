"""Local validation of an MVP-2 diagnostic assessment proposal.

Deliberately a separate module from `diagnostic.validation`. That one enforces MVP-1's rule that a
probe proposal must not assert a verdict about the learner; this contract *requires* a verdict, made
accountable by evidence rather than forbidden. Sharing code between them would mean one set of rules
answering two incompatible questions, and the MVP-1 rules are not relaxed here -- they still apply,
unchanged, to MVP-1 proposals.

This is the agent refusing to emit something indefensible. It is not the authority: Spring's
`ProposalGroundingGate` re-validates every reference against the exact context it built, and would
reject a fabricated identifier whether or not this module existed. Validating in both places is
intentional -- the local pass turns a wasted round trip into a repair attempt, and defence in depth
is the point.
"""

from __future__ import annotations

import json
from typing import Any

from pydantic import ValidationError

from ramals_ai.diagnostic_assessment.contracts import DiagnosticAssessmentProposal

SCHEMA_NOT_JSON = "SCHEMA_NOT_JSON"
SCHEMA_NOT_OBJECT = "SCHEMA_NOT_OBJECT"
EVIDENCE_NOT_IN_CONTEXT = "EVIDENCE_NOT_IN_CONTEXT"
SKILL_NOT_IN_CONTEXT = "SKILL_NOT_IN_CONTEXT"

MAX_REPORTED_ERRORS = 16
"""The envelope carries at most sixteen reason codes, so there is no value in producing more."""


def validate(
    raw: str,
    permitted_evidence_ids: frozenset[str],
    *,
    permitted_skill_codes: frozenset[str] | None = None,
) -> list[str]:
    """Returns validation error codes; an empty list means the proposal is worth sending on.

    Codes rather than prose, so the graph can branch on them and an evaluation harness can count
    them without parsing English.

    ``permitted_evidence_ids`` is the set derived from the grounded context that was actually sent.
    Passing a wider set than was supplied would defeat the check entirely, which is why the agent
    derives it from the same context object it built the prompt from rather than from a parameter a
    caller chooses.

    ``permitted_skill_codes`` is optional because a context may legitimately carry no skill-graph
    facts; when it is absent the skill check is skipped rather than failing everything, and the
    evidence check still applies.
    """
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError, TypeError:
        return [SCHEMA_NOT_JSON]

    if not isinstance(parsed, dict):
        return [SCHEMA_NOT_OBJECT]

    try:
        proposal = DiagnosticAssessmentProposal.model_validate(_with_identity_placeholders(parsed))
    except ValidationError as invalid:
        return _schema_codes(invalid)

    return _grounding_errors(proposal, permitted_evidence_ids, permitted_skill_codes)


def _with_identity_placeholders(parsed: dict[str, Any]) -> dict[str, Any]:
    """Supplies the identifiers the platform owns, so the model is never asked for them.

    ``proposalId``, ``requestId`` and ``agentRunId`` are required by the contract but are assigned
    by the runtime -- a model-supplied identifier would be a correlation identity invented by the
    thing being correlated. They are filled with placeholders for validation and replaced with the
    real run identifiers when the proposal is assembled.
    """
    return {
        "proposalId": "pending",
        "requestId": "pending",
        "agentRunId": "pending",
        **{key: value for key, value in parsed.items() if key not in _RUNTIME_OWNED},
    }


_RUNTIME_OWNED = frozenset({"contractVersion", "proposalId", "requestId", "agentRunId"})


def _schema_codes(invalid: ValidationError) -> list[str]:
    """One stable code per offending field, rather than pydantic's prose.

    Keyed on the field rather than on the failure kind: "the diagnoses array is wrong" is actionable
    for a repair cycle, while the distinction between a missing key and a bad type is not, and
    encoding it would make the codes churn whenever pydantic rewords an error.
    """
    codes: list[str] = []
    for error in invalid.errors():
        location = [part for part in error["loc"] if isinstance(part, str)]
        field = location[0] if location else "PROPOSAL"
        code = f"SCHEMA_INVALID_{_screaming(field)}"
        if code not in codes:
            codes.append(code)
    return codes[:MAX_REPORTED_ERRORS]


def _grounding_errors(
    proposal: DiagnosticAssessmentProposal,
    permitted_evidence_ids: frozenset[str],
    permitted_skill_codes: frozenset[str] | None,
) -> list[str]:
    """The check this module exists for: no claim may rest on evidence that was not supplied.

    A model that invents a plausible identifier is not misbehaving in an exotic way -- it is doing
    the thing language models do, and the contract's answer is that an unrecognised reference is
    simply not a reference.
    """
    codes: list[str] = []
    for diagnosis in proposal.diagnoses:
        if not set(diagnosis.evidenceIds) <= permitted_evidence_ids and (
            EVIDENCE_NOT_IN_CONTEXT not in codes
        ):
            codes.append(EVIDENCE_NOT_IN_CONTEXT)
        if (
            permitted_skill_codes is not None
            and diagnosis.skillCode not in permitted_skill_codes
            and SKILL_NOT_IN_CONTEXT not in codes
        ):
            codes.append(SKILL_NOT_IN_CONTEXT)
    return codes


def _screaming(field: str) -> str:
    """`recommendedNextSkills` -> `RECOMMENDEDNEXTSKILLS`, bounded so a code always fits.

    Crude on purpose. The code is an identifier for humans and dashboards to group on, not a
    reconstruction of the field name.
    """
    return "".join(character for character in field if character.isalnum()).upper()[:40]
