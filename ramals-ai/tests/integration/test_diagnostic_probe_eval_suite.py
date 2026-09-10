"""The `diagnostic-probe-eval-v1` offline semantic-safety suite (M2-ADR-032 step 4).

Hard gates in Doc 07's sense: properties of the system, not of a model. They hold with the
scenario-scripted stub exactly as they would with a live provider, so they run in CI at 100% with no
tolerance. A live-provider semantic-quality run is a separate, release-candidate concern and is not
scored here.

Nothing in this module executes a probe, writes learner state, or feeds DIAGNOSTIC_SELECTION_V1-V5.
The Java half of the same fixture -- the deterministic governance replay -- lives in
`DiagnosticProbeEvalGovernanceContractTests`.
"""

from __future__ import annotations

import json

import pytest

from ramals_ai.evaluation.diagnostic_probe import (
    _CONTRACT_FIELDS,
    load_suite,
    run_scenario,
    run_suite,
)

_REQUIRED_CATEGORIES = {
    "STRONGLY_GROUNDED",
    "COMPETING_MISCONCEPTIONS",
    "INSUFFICIENT_EVIDENCE",
    "STALE_OR_WEAK_EVIDENCE",
    "UNAUTHORIZED_EVIDENCE_INJECTION",
    "UNAUTHORIZED_MISCONCEPTION",
    "UNAUTHORIZED_NODE",
    "CANDIDATE_PROBE_INJECTION",
    "CONFIDENCE_PROBABILITY_INJECTION",
    "DIAGNOSIS_ROOT_CAUSE_LANGUAGE",
    "PROMPT_INJECTION_IN_EVIDENCE",
    "PROVIDER_MODEL_DRIFT",
    "CROSS_DOMAIN_CONTAMINATION",
    "INTERACTION_MISMATCH",
    "REPEATED_EVALUATION",
}

_SUITE = load_suite()


def _scenario_ids() -> list[str]:
    return [s.id for s in _SUITE.scenarios]


# -- structure of the suite itself --------------------------------------------------------------


def test_suite_is_versioned_and_covers_every_required_category() -> None:
    assert _SUITE.version == "diagnostic-probe-eval-v1"
    assert len(_SUITE.scenarios) >= 15
    covered = {s.category for s in _SUITE.scenarios}
    assert covered >= _REQUIRED_CATEGORIES, _REQUIRED_CATEGORIES - covered


def test_scenario_ids_are_unique() -> None:
    ids = _scenario_ids()
    assert len(ids) == len(set(ids))


# -- the hard gate: every scenario behaves as the fixture says --------------------------------


@pytest.mark.parametrize("scenario", _SUITE.scenarios, ids=_scenario_ids())
def test_each_scenario_matches_its_deterministic_expectations(scenario: object) -> None:
    result = run_scenario(scenario)  # type: ignore[arg-type]
    assert result.passed, (
        f"{result.scenario_id}: {list(result.failures)} "
        f"(forwards={result.python_forwards_to_java} "
        f"reasonCodes={list(result.python_reason_codes)} "
        f"semanticViolations={list(result.semantic.semantic_violations)})"
    )


def test_hard_safety_invariants_hold_at_one_hundred_percent() -> None:
    report = run_suite().hard_safety_report()
    failing = [name for name, ok in report.items() if not ok]
    assert failing == [], failing


def test_no_accepted_gate_outcome_scenario_carries_a_confidence_probability_or_ranking_field() -> (
    None
):
    """0 accepted proposals containing confidence/probability/ranking fields; 0 inventing ids."""
    for scenario in _SUITE.scenarios:
        if scenario.expected["gateOutcome"] != "ACCEPTED":
            continue
        payload = scenario.stub_json or {}
        assert set(payload) <= _CONTRACT_FIELDS, f"{scenario.id}: extra keys in an ACCEPTED payload"
        result = run_scenario(scenario)
        m = result.semantic
        assert m.forbidden_field_count == 0
        assert m.extra_field_count == 0
        assert m.invented_evidence_ref_count == 0
        assert m.invented_misconception_ref_count == 0
        assert m.forbidden_terminology_count == 0
        assert not m.candidate_probe_ref_present
        assert not m.invented_node_ref


def test_unauthorized_and_adversarial_categories_never_forward_a_clean_proposal() -> None:
    """100% containment: the Python plane never forwards a schema-valid envelope for a scenario
    whose stub cites an unauthorised reference, adds a forbidden field, or uses forbidden
    terminology."""
    contained = {
        "UNAUTHORIZED_EVIDENCE_INJECTION",
        "UNAUTHORIZED_MISCONCEPTION",
        "CONFIDENCE_PROBABILITY_INJECTION",
        "DIAGNOSIS_ROOT_CAUSE_LANGUAGE",
    }
    for scenario in _SUITE.scenarios:
        if scenario.category not in contained:
            continue
        result = run_scenario(scenario)
        assert not result.python_forwards_to_java, scenario.id


def test_provider_and_model_drift_resolves_to_a_stable_ramals_outcome() -> None:
    for scenario in _SUITE.scenarios:
        if scenario.category != "PROVIDER_MODEL_DRIFT":
            continue
        result = run_scenario(scenario)
        assert result.passed, (scenario.id, list(result.failures))
        # A drift response either does not forward, or forwards a payload Java independently
        # rejects (wrong contract version) -- it never silently becomes an accepted recommendation.
        if result.python_forwards_to_java:
            assert scenario.expected["gateOutcome"] in {"MALFORMED", "REJECTED"}


# -- deterministic and machine-readable -------------------------------------------------------


def test_the_suite_is_deterministic_when_replayed() -> None:
    first = run_suite().to_dict()
    second = run_suite().to_dict()
    assert json.dumps(first, sort_keys=True) == json.dumps(second, sort_keys=True)


def test_machine_readable_result_has_the_documented_shape(tmp_path: object) -> None:
    result = run_suite()
    doc = result.to_dict()

    assert doc["suiteVersion"] == "diagnostic-probe-eval-v1"
    assert doc["scenarioCount"] == len(_SUITE.scenarios)
    assert doc["pass"] is True
    assert set(doc["hardSafety"]) >= {
        "forbiddenFieldContainment",
        "unauthorizedEvidenceContainment",
        "unauthorizedMisconceptionContainment",
        "forbiddenTerminologyContainment",
        "acceptedPathInventsNothing",
        "everyScenarioMatchedExpectations",
    }
    first = doc["scenarios"][0]
    assert set(first) >= {
        "scenarioId",
        "category",
        "expectedJavaGateOutcome",
        "aiPlaneOutcome",
        "pythonForwardsToJava",
        "pythonValidationValid",
        "pythonReasonCodes",
        "semantic",
        "pass",
        "failures",
    }
    assert first["expectedJavaGateOutcome"] in {"ACCEPTED", "REJECTED", "MALFORMED", "ABSENT"}
    assert set(first["semantic"]) >= {
        "schemaCompliant",
        "evidenceGroundingPrecision",
        "targetValid",
        "probeIntentAppropriate",
        "unsupportedClaimCount",
        "forbiddenTerminologyCount",
        "inventedEvidenceRefCount",
        "inventedMisconceptionRefCount",
        "forbiddenFieldCount",
        "semanticPass",
    }

    # A written artefact a CI job or a reviewer can archive; not read back by any runtime path.
    out = tmp_path / "diagnostic-probe-eval-v1-result.json"  # type: ignore[operator]
    out.write_text(json.dumps(doc, indent=2, sort_keys=True), encoding="utf-8")
    assert json.loads(out.read_text(encoding="utf-8"))["pass"] is True


# -- the evaluation harness holds no runtime authority --------------------------------------


def test_the_evaluation_module_has_no_database_or_state_write_path() -> None:
    """Requirement 19: the offline harness reaches no DB and no learner-state writer.

    Checked against the module's actual import graph, not its prose: it may (and does) *name*
    DIAGNOSTIC_SELECTION_V1-V5 in a docstring to say it never touches them.
    """
    import ast
    import pathlib

    import ramals_ai.evaluation.diagnostic_probe as module

    assert module.__file__ is not None
    tree = ast.parse(pathlib.Path(module.__file__).read_text(encoding="utf-8"))
    imported: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.add(node.module)

    forbidden_fragments = (
        "psycopg",
        "sqlalchemy",
        "asyncpg",
        "sqlmodel",
        "diagnostic_selection",
        "mastery",
        "progression",
        "evidence.repository",
    )
    offenders = [
        name for name in imported for fragment in forbidden_fragments if fragment in name.lower()
    ]
    assert offenders == [], offenders
