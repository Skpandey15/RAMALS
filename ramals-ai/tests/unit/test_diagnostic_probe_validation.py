"""Local validation of an MVP-2 diagnostic-probe proposal (M2-ADR-032 step 3).

These mirror the deterministic Java gate's rules on the agent side. The gate remains the
authority -- a proposal is refused there whatever happens here -- so what these tests protect is a
different thing: that a recognisably indefensible proposal is caught before it costs a learner a
wait and the platform a model call, and that the repair loop is given something specific to fix.

Each rule has a refused case and a neighbouring allowed case: a validator that rejects everything is
as useless as one that rejects nothing, and only the allowed case proves the difference.
"""

from __future__ import annotations

import json
import uuid
from typing import Any

import pytest

from ramals_ai.diagnostic_probe.validation import (
    EVIDENCE_NOT_IN_CONTEXT,
    MISCONCEPTION_NOT_IN_CONTEXT,
    RATIONALE_FORBIDDEN_TERMINOLOGY,
    SCHEMA_NOT_JSON,
    SCHEMA_NOT_OBJECT,
    validate,
)

MISC_IN_CONTEXT = str(uuid.uuid4())
MISC_NOT_IN_CONTEXT = str(uuid.uuid4())
NODE_ID = str(uuid.uuid4())
EV_IN_CONTEXT_A = str(uuid.uuid4())
EV_IN_CONTEXT_B = str(uuid.uuid4())
EV_NOT_IN_CONTEXT = str(uuid.uuid4())
PROBE_REF = str(uuid.uuid4())

PERMITTED_EVIDENCE = frozenset({EV_IN_CONTEXT_A, EV_IN_CONTEXT_B})
PERMITTED_MISCONCEPTIONS = frozenset({MISC_IN_CONTEXT})

_RATIONALE = (
    "Governed evidence for this misconception shows one supporting and one inconclusive "
    "observation with no later evidence since the baseline. One additional discriminating "
    "observation on this concept would help narrow the remaining evidentiary ambiguity."
)


def proposal(**overrides: Any) -> str:
    payload: dict[str, Any] = {
        "targetMisconceptionId": MISC_IN_CONTEXT,
        "targetNode": {"kind": "CONCEPT", "id": NODE_ID},
        "probeIntent": "DISCRIMINATE_BETWEEN_EVIDENCE_STATES",
        "candidateProbeRef": None,
        "evidenceRefs": [EV_IN_CONTEXT_A],
        "rationale": _RATIONALE,
    }
    payload.update(overrides)
    return json.dumps(payload)


def check(raw: str) -> list[str]:
    return validate(raw, PERMITTED_EVIDENCE, PERMITTED_MISCONCEPTIONS)


# -- the allowed case ----------------------------------------------------------------------------


def test_a_well_formed_grounded_proposal_passes() -> None:
    assert check(proposal()) == []


def test_multiple_in_context_evidence_refs_pass() -> None:
    assert check(proposal(evidenceRefs=[EV_IN_CONTEXT_A, EV_IN_CONTEXT_B])) == []


def test_the_other_probe_intent_passes() -> None:
    assert check(proposal(probeIntent="COLLECT_ADDITIONAL_MISCONCEPTION_EVIDENCE")) == []


# -- shape -------------------------------------------------------------------------------------


def test_non_json_is_rejected() -> None:
    assert check("not json at all") == [SCHEMA_NOT_JSON]


def test_a_json_array_is_rejected() -> None:
    assert check("[1, 2, 3]") == [SCHEMA_NOT_OBJECT]


def test_a_missing_required_field_is_a_schema_code() -> None:
    payload = json.loads(proposal())
    del payload["probeIntent"]
    codes = check(json.dumps(payload))
    assert codes == ["SCHEMA_INVALID_PROBEINTENT"]


def test_an_unknown_field_is_rejected() -> None:
    # extra="forbid" on the contract; not one of the specially-named forbidden keys.
    assert check(proposal(surpriseField="x")) == ["SCHEMA_INVALID_SURPRISEFIELD"]


# -- the model must assert nothing about the learner (M2-ADR-032 3) --------------------------------


@pytest.mark.parametrize(
    "field",
    ["confidence", "probability", "score", "rank", "ranking", "diagnosis", "rootCause"],
)
def test_a_forbidden_verdict_field_is_named_specifically(field: str) -> None:
    codes = check(proposal(**{field: 0.9}))
    assert codes == [f"FORBIDDEN_FIELD_{field.upper()}"]


def test_forbidden_confidence_is_caught_even_with_an_otherwise_valid_body() -> None:
    codes = check(proposal(confidence=0.92))
    assert "FORBIDDEN_FIELD_CONFIDENCE" in codes


# -- grounding: E_proposed subset of E_allowed (M2-ADR-032 11) ------------------------------------


def test_an_evidence_ref_outside_the_context_is_rejected() -> None:
    assert check(proposal(evidenceRefs=[EV_NOT_IN_CONTEXT])) == [EVIDENCE_NOT_IN_CONTEXT]


def test_a_mix_of_in_and_out_of_context_evidence_is_rejected() -> None:
    codes = check(proposal(evidenceRefs=[EV_IN_CONTEXT_A, EV_NOT_IN_CONTEXT]))
    assert codes == [EVIDENCE_NOT_IN_CONTEXT]


def test_case_variant_evidence_ref_still_matches() -> None:
    assert check(proposal(evidenceRefs=[EV_IN_CONTEXT_A.upper()])) == []


# -- grounding: M in M_allowed (M2-ADR-032 12) ---------------------------------------------------


def test_a_misconception_outside_the_context_is_rejected() -> None:
    assert check(proposal(targetMisconceptionId=MISC_NOT_IN_CONTEXT)) == [
        MISCONCEPTION_NOT_IN_CONTEXT
    ]


def test_case_variant_misconception_id_still_matches() -> None:
    assert check(proposal(targetMisconceptionId=MISC_IN_CONTEXT.upper())) == []


# -- rationale terminology (M2-ADR-032 9) --------------------------------------------------------


@pytest.mark.parametrize(
    "rationale",
    [
        "This misconception is more likely than the other to explain the errors.",
        "There is a high probability the learner holds this misconception.",
        "The evidence suggests a 70 percent chance of this misconception.",
        "This pattern indicates the ROOT_CAUSE of the learner's errors.",
        "The earlier finding is now CONFIRMED by later observations.",
    ],
)
def test_probability_or_resolution_language_in_the_rationale_is_rejected(rationale: str) -> None:
    codes = check(proposal(rationale=rationale))
    assert codes == [RATIONALE_FORBIDDEN_TERMINOLOGY]


def test_a_bounded_neutral_rationale_is_allowed() -> None:
    codes = check(
        proposal(
            rationale=(
                "The governed evidence for this misconception is mixed: one supporting and one "
                "contradictory later observation. A further observation targeting this concept "
                "would help distinguish the evidence states."
            )
        )
    )
    assert codes == []


# -- candidate probe ref: shape only here; authorisation is the Java gate's (M2-ADR-032 13) -------


def test_a_uuid_candidate_probe_ref_is_schema_valid_here() -> None:
    # Local validation does not know the authorised probe set (it is empty in step 3); the Java
    # gate rejects any concrete value. This layer only checks it is a well-formed uuid or null.
    assert check(proposal(candidateProbeRef=PROBE_REF)) == []


def test_a_non_uuid_candidate_probe_ref_is_a_schema_code() -> None:
    assert check(proposal(candidateProbeRef="not-a-uuid")) == ["SCHEMA_INVALID_CANDIDATEPROBEREF"]
