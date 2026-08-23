"""M2-T10 golden scenarios and provider-independent evaluation thresholds."""

from pathlib import Path

from ramals_ai.evaluation.diagnostic import (
    DiagnosticObservation,
    evaluate_diagnostics,
    load_diagnostic_cases,
    semantic_signature,
)

DATASET = (
    Path(__file__).resolve().parents[3] / "evaluation" / "mvp2" / "mvp2-diagnostic-golden.v1.json"
)


def _proposal(case: object, reason: str) -> dict[str, object]:
    classifications = case.expected_classifications  # type: ignore[attr-defined]
    evidence = sorted(case.evidence_ids)  # type: ignore[attr-defined]
    return {
        "diagnoses": [
            {
                "skillCode": skill,
                "classification": classification,
                "reason": reason,
                "evidenceIds": evidence,
            }
            for skill, classification in classifications.items()
        ],
        "recommendedNextSkills": sorted(case.expected_next_skills),  # type: ignore[attr-defined]
        "confidence": 0.8,
    }


def test_golden_diagnostic_thresholds_pass_for_weak_strong_inconsistent_and_cold_start() -> None:
    cases = load_diagnostic_cases(DATASET)
    observations = tuple(
        DiagnosticObservation(
            case,
            (_proposal(case, "first provider wording"), _proposal(case, "different wording")),
            (True, True),
        )
        for case in cases
    )

    result = evaluate_diagnostics(observations)

    assert result.passes()
    assert str(result.schema_validity) == "1"
    assert str(result.evidence_support) == "1"
    assert str(result.stability) == "1"
    assert str(result.business_usefulness) == "1"


def test_single_dimension_perturbation_has_an_explainable_semantic_delta() -> None:
    case = load_diagnostic_cases(DATASET)[0]
    baseline = _proposal(case, "low mastery and repeated incorrect attempts")
    perturbed = _proposal(case, "mastery is now high")
    perturbed["diagnoses"][0]["classification"] = "STRONG"  # type: ignore[index]
    perturbed["recommendedNextSkills"] = []

    before, before_next = semantic_signature(baseline)
    after, after_next = semantic_signature(perturbed)

    assert before == (("offset-management", "WEAK"),)
    assert after == (("offset-management", "STRONG"),)
    assert before_next == ("offset-management",)
    assert after_next == ()


def test_unsupported_evidence_fails_the_release_threshold() -> None:
    case = load_diagnostic_cases(DATASET)[0]
    unsupported = _proposal(case, "plausible wording cannot replace evidence")
    unsupported["diagnoses"][0]["evidenceIds"] = ["fabricated"]  # type: ignore[index]

    result = evaluate_diagnostics((DiagnosticObservation(case, (unsupported,), (True,)),))

    assert result.evidence_support == 0
    assert not result.passes()
