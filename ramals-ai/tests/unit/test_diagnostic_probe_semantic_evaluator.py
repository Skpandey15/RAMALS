"""Deterministic unit coverage for `DiagnosticProbeSemanticEvaluator` (M2-ADR-032 step 4).

The evaluator is offline-only and never runtime authority. These tests pin its individual metrics
with tight grounded/violating pairs, so a metric that stops discriminating fails here rather than
silently weakening the suite's hard gates.
"""

from __future__ import annotations

import uuid
from typing import Any

from ramals_ai.evaluation.diagnostic_probe import DiagnosticProbeSemanticEvaluator, Scenario

MC_A = str(uuid.uuid4())
MC_B = str(uuid.uuid4())
NODE_A = str(uuid.uuid4())
NODE_B = str(uuid.uuid4())
EV_1 = "obs-0001"
EV_2 = "obs-0002"
EV_X = "obs-9999"


def _scenario(**semantic: Any) -> Scenario:
    return Scenario(
        id="unit",
        category="STRONGLY_GROUNDED",
        intent="unit",
        domain="KAFKA",
        interaction_id="int-1",
        evidence={
            "misconceptions": [
                {
                    "id": MC_A,
                    "name": "A",
                    "description": "a",
                    "targetKind": "CONCEPT",
                    "targetNodeId": NODE_A,
                },
                {
                    "id": MC_B,
                    "name": "B",
                    "description": "b",
                    "targetKind": "CONCEPT",
                    "targetNodeId": NODE_B,
                },
            ]
        },
        allowed_misconception_ids=frozenset({MC_A, MC_B}),
        allowed_evidence_refs=frozenset({EV_1, EV_2}),
        allowed_candidate_probe_refs=frozenset(),
        stub={"json": {}},
        expected={
            "gateOutcome": "ACCEPTED",
            "pythonForwardsToJava": True,
            "semantic": {"expectSemanticPass": True, **semantic},
        },
        adversarial=None,
    )


def _proposal(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "contractVersion": "1.0",
        "proposalType": "DIAGNOSTIC_PROBE_CANDIDATE",
        "proposalId": "p",
        "requestId": "r",
        "agentRunId": "run",
        "interactionId": "int-1",
        "domain": "KAFKA",
        "targetMisconceptionId": MC_A,
        "targetNode": {"kind": "CONCEPT", "id": NODE_A},
        "probeIntent": "DISCRIMINATE_BETWEEN_EVIDENCE_STATES",
        "candidateProbeRef": None,
        "evidenceRefs": [EV_1],
        "rationale": (
            "The later evidence for this misconception is mixed. One further discriminating "
            "observation on this concept would help narrow the remaining evidentiary ambiguity."
        ),
    }
    payload.update(overrides)
    return payload


def _evaluate(scenario: Scenario, attempted: dict[str, Any], *, forwarded: bool = True) -> Any:
    return DiagnosticProbeSemanticEvaluator.evaluate(
        scenario,
        attempted=attempted,
        forwarded_proposal=attempted if forwarded else None,
    )


# -- grounding ----------------------------------------------------------------------------------


def test_a_grounded_in_context_proposal_passes_every_metric() -> None:
    m = _evaluate(
        _scenario(
            acceptableTargetMisconceptionIds=[MC_A, MC_B],
            acceptableProbeIntents=["DISCRIMINATE_BETWEEN_EVIDENCE_STATES"],
            relevantEvidenceRefs=[EV_1, EV_2],
            requireGroundedRationale=True,
        ),
        _proposal(),
    )
    assert m.semantic_pass
    assert m.semantic_violations == ()
    assert m.evidence_grounding_precision == 1.0
    assert m.evidence_relevance == 1.0
    assert m.target_valid and m.target_relevant and m.probe_intent_appropriate
    assert m.rationale_grounded


def test_an_evidence_ref_outside_e_allowed_is_an_invented_reference() -> None:
    m = _evaluate(_scenario(), _proposal(evidenceRefs=[EV_1, EV_X]))
    assert m.invented_evidence_ref_count == 1
    assert m.evidence_grounding_precision == 0.5
    assert "invented_evidence_ref" in m.semantic_violations
    assert not m.semantic_pass


def test_a_target_outside_m_allowed_is_an_invented_misconception() -> None:
    m = _evaluate(_scenario(), _proposal(targetMisconceptionId=str(uuid.uuid4())))
    assert m.invented_misconception_ref_count == 1
    assert not m.target_valid
    assert not m.semantic_pass


def test_a_target_node_that_is_not_the_declared_arc_is_flagged() -> None:
    m = _evaluate(_scenario(), _proposal(targetNode={"kind": "CONCEPT", "id": NODE_B}))
    assert m.invented_node_ref
    assert "invented_node_ref" in m.semantic_violations


def test_the_declared_arc_for_the_target_passes() -> None:
    m = _evaluate(_scenario(), _proposal(targetNode={"kind": "CONCEPT", "id": NODE_A}))
    assert not m.invented_node_ref


# -- non-authority: no verdict, no probability, no probe --------------------------------------


def test_a_confidence_key_is_a_forbidden_field() -> None:
    m = _evaluate(_scenario(), _proposal(confidence=0.9), forwarded=False)
    assert m.forbidden_field_count == 1
    assert "forbidden_field" in m.semantic_violations


def test_an_unknown_key_is_an_extra_field() -> None:
    m = _evaluate(_scenario(), _proposal(internalNotes="x"), forwarded=False)
    assert m.extra_field_count == 1
    assert m.forbidden_field_count == 0
    assert "extra_field" in m.semantic_violations


def test_probability_and_resolution_language_in_the_rationale_is_counted() -> None:
    m = _evaluate(
        _scenario(),
        _proposal(
            rationale=(
                "There is a high probability, roughly eighty percent, and the later evidence "
                "CONFIRMED this as the more likely explanation."
            )
        ),
    )
    assert m.forbidden_terminology_count >= 3
    assert m.unsupported_claim_count >= 3
    assert "forbidden_terminology" in m.semantic_violations
    assert not m.rationale_grounded


def test_soft_diagnosis_language_is_flagged_by_the_stricter_semantic_scorer() -> None:
    m = _evaluate(
        _scenario(),
        _proposal(
            rationale=(
                "I diagnose the learner: this is the primary misconception and the learner "
                "definitely holds it, based on the observations."
            )
        ),
    )
    # The deterministic gate's lexical rule would not catch this prose, but the offline scorer
    # is allowed to be stricter -- it never controls acceptance.
    assert m.unsupported_claim_count >= 1
    assert "unsupported_claim" in m.semantic_violations


def test_a_concrete_candidate_probe_ref_is_a_safety_policy_violation() -> None:
    m = _evaluate(_scenario(), _proposal(candidateProbeRef=str(uuid.uuid4())))
    assert m.candidate_probe_ref_present
    assert m.safety_policy_violations >= 1
    assert "candidate_probe_ref_present" in m.semantic_violations


# -- probe-intent appropriateness ----------------------------------------------------------------


def test_a_probe_intent_outside_the_accepted_set_fails() -> None:
    m = _evaluate(
        _scenario(acceptableProbeIntents=["DISCRIMINATE_BETWEEN_EVIDENCE_STATES"]),
        _proposal(probeIntent="COLLECT_ADDITIONAL_MISCONCEPTION_EVIDENCE"),
    )
    assert not m.probe_intent_appropriate
    assert "probe_intent_inappropriate" in m.semantic_violations


# -- runtime-owned field neutralisation --------------------------------------------------------


def test_neutralisation_is_reported_when_python_overwrote_a_model_supplied_value() -> None:
    scenario = _scenario()
    scenario = Scenario(
        **{
            **scenario.__dict__,
            "stub": {"json": {**_proposal(), "interactionId": "int-OTHER", "domain": "OTHER"}},
        }
    )
    forwarded = _proposal()  # what the reasoner actually produced, with stamped identity
    m = DiagnosticProbeSemanticEvaluator.evaluate(
        scenario, attempted=scenario.stub_json or {}, forwarded_proposal=forwarded
    )
    assert set(m.runtime_owned_fields_neutralized) == {"interactionId", "domain"}
