"""Diagnostic Probe Reasoner V1 bounded-execution tests (M2-ADR-032 step 3).

The reasoner reads bounded H6/H7 governed evidence through the MCP-3 client, asks the model for one
next diagnostic-probe candidate, and returns an ``AIProposalEnvelope``. It asserts nothing about the
learner and holds no authority: Java's deterministic ``DiagnosticProbeProposalGate`` decides whether
a recommendation has any effect, and every failure here resolves to a envelope Java maps to
``ABSENT``.

These tests pin the AI-plane half:

* a groundable read + a valid model answer -> a non-authoritative envelope carrying the proposal;
* every unavailability (no domain, no delegated context, an MCP error, empty H6/H7) -> ``no model
  call`` and ``schemaValid=False`` so Java resolves ``ABSENT``;
* a model that tries to assert a verdict (a ``confidence`` field, probability / ranking language)
  -> the proposal is refused locally before it leaves the process.
"""

from __future__ import annotations

import json
import uuid
from typing import Any

import pytest

from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import AIRequestEnvelope, TrustLevel
from ramals_ai.diagnostic_probe.reasoner import DiagnosticProbeReasoner
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import Message, ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.mcp.client import RamalsMcpReadClient
from ramals_ai.mcp.context import McpExecutionContext
from ramals_ai.mcp.errors import McpError, McpErrorCode
from ramals_ai.mcp.models import McpDiagnosticReport, McpLongitudinalReport

DOMAIN = "KAFKA"

MISC_A = str(uuid.uuid4())
MISC_B = str(uuid.uuid4())
NODE_A = str(uuid.uuid4())
NODE_B = str(uuid.uuid4())
EV_1 = str(uuid.uuid4())
EV_2 = str(uuid.uuid4())


# -- test doubles ---------------------------------------------------------------------------------


class ScriptedProvider(FakeProvider):
    """Returns a fixed completion and records the prompts it was given."""

    def __init__(self, payload: str) -> None:
        super().__init__()
        self.payload = payload
        self.prompts: list[tuple[Message, ...]] = []

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        self.prompts.append(request.messages)
        return ProviderResponse(
            text=self.payload, input_tokens=120, output_tokens=48, cached_input_tokens=0
        )


class StubMcpReadClient(RamalsMcpReadClient):
    """A stand-in exposing only the two reads the reasoner makes.

    Subclasses ``RamalsMcpReadClient`` for the type, but deliberately does not call its
    constructor: that one opens a workload-token provider and refuses when MCP is disabled, and a
    unit test needs neither. Only ``current_domain_report`` and ``longitudinal_summary`` are
    exercised by the reasoner.
    """

    def __init__(
        self,
        h6: McpDiagnosticReport | None = None,
        h7: McpLongitudinalReport | None = None,
        *,
        error: McpError | None = None,
    ) -> None:
        self._h6 = h6 if h6 is not None else diagnostic_report()
        self._h7 = h7 if h7 is not None else longitudinal_report()
        self._error = error
        self.calls: list[tuple[str, str]] = []

    async def current_domain_report(
        self, _context: McpExecutionContext, *, domain_code: str
    ) -> McpDiagnosticReport:
        self.calls.append(("current_domain_report", domain_code))
        if self._error is not None:
            raise self._error
        return self._h6

    async def longitudinal_summary(
        self, _context: McpExecutionContext, *, domain_code: str
    ) -> McpLongitudinalReport:
        self.calls.append(("longitudinal_summary", domain_code))
        if self._error is not None:
            raise self._error
        return self._h7


# -- fixtures -----------------------------------------------------------------------------------


def diagnostic_report(
    *, status: str = "HAS_EVIDENCE", findings: bool = True
) -> McpDiagnosticReport:
    payload: dict[str, Any] = {
        "reportMode": "CURRENT_DOMAIN",
        "diagnosticDataStatus": status,
        "domainCode": DOMAIN,
        "attemptId": None,
        "generatedAt": "2026-09-09T00:00:00Z",
        "misconceptionFindings": (
            [
                {
                    "misconceptionId": MISC_A,
                    "name": "Confuses partition with replica",
                    "description": "A learner treats a partition and a replica as the same thing.",
                    "targetType": "CONCEPT",
                    "targetId": NODE_A,
                    "objectiveContext": None,
                    "conceptContext": {"conceptId": NODE_A, "name": "Partitioning"},
                    "subConceptContext": None,
                    "evidenceSummary": {
                        "supportingCount": 1,
                        "contradictoryCount": 1,
                        "inconclusiveCount": 0,
                    },
                    "confidenceState": "ASSESSED",
                    "confidence": {
                        "band": "MODERATE",
                        "policyVersion": "DIAGNOSTIC_CONFIDENCE_V1",
                        "computedAt": "2026-09-08T00:00:00Z",
                    },
                }
            ]
            if findings
            else []
        ),
        "mastery": [],
    }
    return McpDiagnosticReport.model_validate(payload)


def longitudinal_report(*, observation_ids: list[str] | None = None) -> McpLongitudinalReport:
    ids = observation_ids if observation_ids is not None else [EV_1, EV_2]
    payload: dict[str, Any] = {
        "domainCode": DOMAIN,
        "generatedAt": "2026-09-09T00:00:00Z",
        "findings": [
            {
                "misconceptionId": MISC_A,
                "name": "Confuses partition with replica",
                "description": "A learner treats a partition and a replica as the same thing.",
                "targetType": "CONCEPT",
                "targetId": NODE_A,
                "objectiveContext": None,
                "conceptContext": None,
                "subConceptContext": None,
                "dataStatus": "HAS_BASELINE" if ids else "NO_BASELINE",
                "baseline": (
                    {"evidenceStrength": "LOW", "computedAt": "2026-09-01T00:00:00Z"}
                    if ids
                    else None
                ),
                "state": "LATER_MIXED_EVIDENCE" if ids else None,
                "laterEvidence": {
                    "supportingCount": 1,
                    "contradictoryCount": 1,
                    "inconclusiveCount": 0,
                },
                "laterEvidenceObservationIds": ids,
                "latestConfidence": None,
                "confidenceCoverage": None,
                "policyVersion": "LONGITUDINAL_EVIDENCE_V1",
            }
        ],
    }
    return McpLongitudinalReport.model_validate(payload)


def envelope(*, with_domain: bool = True) -> AIRequestEnvelope:
    payload: dict[str, Any] = {
        "contractVersion": "1.0",
        "interactionId": str(uuid.uuid7()),
        "requestId": str(uuid.uuid4()),
        "learner": {"learnerRef": "must-not-leak", "locale": "en-IN"},
        "constraints": {"interactionClass": "INTERACTIVE_AI", "deadlineMs": 9000},
    }
    if with_domain:
        payload["domainContext"] = {
            "domainCode": DOMAIN,
            "domainType": "TECHNOLOGY",
            "curriculumVersion": "v1",
        }
    return AIRequestEnvelope.model_validate(payload)


def mcp_context() -> McpExecutionContext:
    return McpExecutionContext(
        delegated_context_token="delegated-token",  # noqa: S106 - test literal, not a secret
        interaction_id="int-probe-1",
        deadline=Deadline.in_ms(9000),
    )


def good_output(**overrides: Any) -> str:
    payload: dict[str, Any] = {
        "targetMisconceptionId": MISC_A,
        "targetNode": {"kind": "CONCEPT", "id": NODE_A},
        "probeIntent": "DISCRIMINATE_BETWEEN_EVIDENCE_STATES",
        "candidateProbeRef": None,
        "evidenceRefs": [EV_1],
        "rationale": (
            "The governed evidence for this misconception is mixed: one supporting and one "
            "contradictory later observation since the baseline. One further discriminating "
            "observation on this concept would help narrow the remaining evidentiary ambiguity."
        ),
    }
    payload.update(overrides)
    return json.dumps(payload)


def reasoner(payload: str) -> tuple[DiagnosticProbeReasoner, ScriptedProvider, StubMcpReadClient]:
    provider = ScriptedProvider(payload)
    client = StubMcpReadClient()
    agent = DiagnosticProbeReasoner(
        LLMGateway(provider), route=ModelRoute.CI_FAKE, mcp_client=client
    )
    return agent, provider, client


# -- the groundable, valid path -----------------------------------------------------------------


def test_a_valid_recommendation_is_returned_as_a_non_authoritative_proposal() -> None:
    agent, _provider, client = reasoner(good_output())

    proposal = agent.propose(
        envelope(), deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )

    assert proposal.validation is not None and proposal.validation.schemaValid is True
    assert proposal.trustLevel is TrustLevel.NON_AUTHORITATIVE
    assert proposal.agentType.value == "DIAGNOSTIC"
    assert proposal.promptTemplateId == "DIAGNOSTIC_PROBE_CANDIDATE"
    assert proposal.promptVersion == "DIAGNOSTIC_PROBE_PROMPT_V1"
    assert proposal.reasonCodes is None
    # both governed reads happened, scoped to the request's domain
    assert client.calls == [
        ("current_domain_report", DOMAIN),
        ("longitudinal_summary", DOMAIN),
    ]


def test_the_returned_proposal_matches_the_frozen_v1_contract() -> None:
    agent, _provider, _client = reasoner(good_output())

    proposal = agent.propose(
        envelope(), deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )
    body = proposal.proposal

    assert body["contractVersion"] == "1.0"
    assert body["proposalType"] == "DIAGNOSTIC_PROBE_CANDIDATE"
    assert body["targetMisconceptionId"] == MISC_A
    assert body["targetNode"] == {"kind": "CONCEPT", "id": NODE_A}
    assert body["evidenceRefs"] == [EV_1]
    assert "confidence" not in body
    assert "candidateProbeRef" not in body  # None -> absent, matching the fixture


def test_runtime_owns_the_identity_fields_not_the_model() -> None:
    request = envelope()
    agent, _provider, _client = reasoner(
        good_output(
            proposalId="model-supplied",
            interactionId="model-supplied",
            domain="MODEL_SUPPLIED",
        )
    )

    proposal = agent.propose(
        request, deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )

    assert proposal.proposal["proposalId"] == request.requestId
    assert proposal.proposal["interactionId"] == request.interactionId
    assert proposal.proposal["domain"] == DOMAIN


def test_the_prompt_carries_only_the_bounded_projection() -> None:
    agent, provider, _client = reasoner(good_output())

    agent.propose(envelope(), deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context())
    rendered = provider.prompts[-1][1].content

    assert MISC_A in rendered
    assert EV_1 in rendered and EV_2 in rendered
    assert "must-not-leak" not in rendered
    assert "delegated-token" not in rendered
    assert "requestId" not in rendered


def test_repeated_evaluation_is_idempotent_on_the_proposal_id() -> None:
    request = envelope()
    agent, _provider, _client = reasoner(good_output())

    first = agent.propose(
        request, deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )
    second = agent.propose(
        request, deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )

    assert first.proposalId == second.proposalId == request.requestId
    # agentRunId is deliberately one-per-execution; everything the recommendation asserts is stable.
    stable = {"agentRunId"}
    assert {k: v for k, v in first.proposal.items() if k not in stable} == {
        k: v for k, v in second.proposal.items() if k not in stable
    }


# -- unavailability: no model call, resolves to ABSENT -----------------------------------------


def _assert_absent(proposal: Any, *, reason_fragment: str) -> None:
    assert proposal.validation is not None and proposal.validation.schemaValid is False
    assert proposal.proposal == {}
    assert proposal.reasonCodes is not None
    assert any(reason_fragment in code.root for code in proposal.reasonCodes)


def test_no_domain_context_yields_no_model_call_and_absent() -> None:
    agent, provider, client = reasoner(good_output())

    proposal = agent.propose(
        envelope(with_domain=False),
        deadline=Deadline.in_ms(9000),
        mcp_execution_context=mcp_context(),
    )

    _assert_absent(proposal, reason_fragment="DOMAIN_ABSENT")
    assert provider.prompts == []
    assert client.calls == []


def test_no_delegated_mcp_context_yields_no_model_call_and_absent() -> None:
    agent, provider, _client = reasoner(good_output())

    proposal = agent.propose(envelope(), deadline=Deadline.in_ms(9000), mcp_execution_context=None)

    _assert_absent(proposal, reason_fragment="MCP_CONTEXT_MISSING")
    assert provider.prompts == []


def test_no_mcp_client_configured_yields_absent() -> None:
    provider = ScriptedProvider(good_output())
    agent = DiagnosticProbeReasoner(LLMGateway(provider), route=ModelRoute.CI_FAKE, mcp_client=None)

    proposal = agent.propose(
        envelope(), deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )

    _assert_absent(proposal, reason_fragment="MCP_CONTEXT_MISSING")
    assert provider.prompts == []


@pytest.mark.parametrize(
    "code",
    [
        McpErrorCode.MCP_UNAVAILABLE,
        McpErrorCode.MCP_DEADLINE_EXCEEDED,
        McpErrorCode.MCP_DELEGATED_CONTEXT_MISSING,
        McpErrorCode.MCP_TRANSPORT_ERROR,
    ],
)
def test_an_mcp_read_failure_yields_no_model_call_and_absent(code: McpErrorCode) -> None:
    provider = ScriptedProvider(good_output())
    client = StubMcpReadClient(error=McpError(code, "read refused"))
    agent = DiagnosticProbeReasoner(
        LLMGateway(provider), route=ModelRoute.CI_FAKE, mcp_client=client
    )

    proposal = agent.propose(
        envelope(), deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )

    _assert_absent(proposal, reason_fragment=code.value)
    assert provider.prompts == []


def test_h6_no_evidence_yields_no_model_call_and_absent() -> None:
    provider = ScriptedProvider(good_output())
    client = StubMcpReadClient(h6=diagnostic_report(status="NO_EVIDENCE", findings=False))
    agent = DiagnosticProbeReasoner(
        LLMGateway(provider), route=ModelRoute.CI_FAKE, mcp_client=client
    )

    proposal = agent.propose(
        envelope(), deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )

    _assert_absent(proposal, reason_fragment="NO_GROUNDABLE_EVIDENCE")
    assert provider.prompts == []


def test_no_h7_baseline_means_empty_e_allowed_and_absent() -> None:
    # A learner with H6 findings but no post-baseline H7 observations: E_allowed is empty, so a
    # citation cannot be grounded and the reasoner resolves to ABSENT (documented limitation).
    provider = ScriptedProvider(good_output())
    client = StubMcpReadClient(h7=longitudinal_report(observation_ids=[]))
    agent = DiagnosticProbeReasoner(
        LLMGateway(provider), route=ModelRoute.CI_FAKE, mcp_client=client
    )

    proposal = agent.propose(
        envelope(), deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )

    _assert_absent(proposal, reason_fragment="NO_GROUNDABLE_EVIDENCE")
    assert provider.prompts == []


# -- the model must assert nothing about the learner -------------------------------------------


def test_a_model_that_emits_a_confidence_field_is_refused_locally() -> None:
    agent, _provider, _client = reasoner(good_output(confidence=0.93))

    proposal = agent.propose(
        envelope(), deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )

    assert proposal.validation is not None and proposal.validation.schemaValid is False
    assert proposal.reasonCodes is not None
    assert any("FORBIDDEN_FIELD_CONFIDENCE" in code.root for code in proposal.reasonCodes)
    assert proposal.proposal == {}


def test_a_model_that_ranks_misconceptions_in_the_rationale_is_refused_locally() -> None:
    agent, _provider, _client = reasoner(
        good_output(
            rationale=(
                "This misconception is more likely than the others to explain the errors, so "
                "another observation would confirm it."
            )
        )
    )

    proposal = agent.propose(
        envelope(), deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )

    assert proposal.validation is not None and proposal.validation.schemaValid is False
    assert proposal.reasonCodes is not None
    assert any("RATIONALE_FORBIDDEN_TERMINOLOGY" in code.root for code in proposal.reasonCodes)


def test_a_model_that_cites_evidence_outside_the_context_is_refused_locally() -> None:
    agent, _provider, _client = reasoner(good_output(evidenceRefs=[str(uuid.uuid4())]))

    proposal = agent.propose(
        envelope(), deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )

    assert proposal.validation is not None and proposal.validation.schemaValid is False
    assert proposal.reasonCodes is not None
    assert any("EVIDENCE_NOT_IN_CONTEXT" in code.root for code in proposal.reasonCodes)


def test_a_model_that_targets_a_misconception_outside_the_context_is_refused_locally() -> None:
    agent, _provider, _client = reasoner(good_output(targetMisconceptionId=str(uuid.uuid4())))

    proposal = agent.propose(
        envelope(), deadline=Deadline.in_ms(9000), mcp_execution_context=mcp_context()
    )

    assert proposal.validation is not None and proposal.validation.schemaValid is False
    assert proposal.reasonCodes is not None
    assert any("MISCONCEPTION_NOT_IN_CONTEXT" in code.root for code in proposal.reasonCodes)


# -- the module holds no authority --------------------------------------------------------------


def test_the_diagnostic_probe_package_imports_no_database_or_write_primitive() -> None:
    """M2-ADR-032 step 3 requirement 19: the AI plane's reasoner has no direct DB write path.

    The repo-wide ``test_no_database_access`` proves no driver is importable at all; this narrower
    check proves the reasoner did not quietly pull in the authoritative writers or an ORM even if a
    future dependency made one importable.
    """
    import ramals_ai.diagnostic_probe.contracts as contracts_mod
    import ramals_ai.diagnostic_probe.prompt as prompt_mod
    import ramals_ai.diagnostic_probe.reasoner as reasoner_mod
    import ramals_ai.diagnostic_probe.validation as validation_mod

    forbidden = ("psycopg", "sqlalchemy", "asyncpg", "sqlmodel", "alembic")
    for module in (contracts_mod, prompt_mod, validation_mod, reasoner_mod):
        source = module.__file__
        assert source is not None
        text = open(source, encoding="utf-8").read()  # noqa: PTH123, SIM115
        for name in forbidden:
            assert name not in text, f"{module.__name__} references {name}"
