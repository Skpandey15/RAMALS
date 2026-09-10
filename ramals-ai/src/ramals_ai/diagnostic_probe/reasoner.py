"""Diagnostic Probe Reasoner V1 (M2-ADR-032 step 3).

Runtime sequence, on one delegated AI request:

1. resolve the domain from the request envelope;
2. read bounded H6/H7 governed evidence through the existing MCP-3 read client, using the
   interaction's own delegated learner-context credential -- the reasoner does its *own* bounded
   reads for deterministic evidence acquisition, it does not hand the model a tool;
3. project that evidence to a bounded prompt context (``M_allowed`` misconceptions, ``E_allowed``
   citable evidence ids, the closed probe-intent set);
4. run one bounded graph execution over the existing LLM gateway, no tools, with the step-3
   validator;
5. return an ``AIProposalEnvelope``.

It has no database access, computes no diagnosis or mastery, selects and executes no probe, and
holds no reference to anything that writes learner state. Every failure -- no delegated context, an
MCP error, an empty or invalid model response -- yields an envelope whose ``validation.schemaValid``
is false, which the internal API turns into a 422 and Java resolves to ``ABSENT`` with the
deterministic diagnostic path unchanged. No accepted recommendation is consumed anywhere in step 3.
"""

from __future__ import annotations

import json
from typing import Any

import anyio

from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import (
    AgentType,
    AIProposalEnvelope,
    AIRequestEnvelope,
    ContractVersion,
    ReasonCode,
    TrustLevel,
    Usage,
    Validation,
)
from ramals_ai.diagnostic_probe import prompt as diagnostic_probe_prompt
from ramals_ai.diagnostic_probe.contracts import DiagnosticProbeProposal
from ramals_ai.diagnostic_probe.validation import validate
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import GatewayExecutionPolicy, LLMGateway
from ramals_ai.graph.runtime import GraphRun
from ramals_ai.graph.state import AgentState
from ramals_ai.mcp.client import RamalsMcpReadClient
from ramals_ai.mcp.context import McpExecutionContext
from ramals_ai.mcp.errors import McpError, McpErrorCode
from ramals_ai.mcp.models import McpDiagnosticReport, McpLongitudinalReport
from ramals_ai.prompting.templates import PromptRegister, PromptTemplateId

_H6_HAS_EVIDENCE = "HAS_EVIDENCE"

_MCP_UNAVAILABLE = "DIAGNOSTIC_PROBE_MCP_UNAVAILABLE"
_MCP_CONTEXT_MISSING = "DIAGNOSTIC_PROBE_MCP_CONTEXT_MISSING"
_NO_DOMAIN = "DIAGNOSTIC_PROBE_DOMAIN_ABSENT"
_NO_GROUNDABLE_EVIDENCE = "DIAGNOSTIC_PROBE_NO_GROUNDABLE_EVIDENCE"

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


class DiagnosticProbeReasoner:
    """Produces one non-authoritative diagnostic-probe recommendation."""

    agent_type = AgentType.DIAGNOSTIC
    """Reused deliberately, exactly as ``DiagnosticAgent`` and ``DiagnosticAssessmentAgent`` do.

    ``AgentType`` is generated from the OpenAPI contract; inventing a member here would be a
    transport-contract change made by an agent implementation. The recorded execution is
    unambiguous through ``promptTemplateId`` = ``DIAGNOSTIC_PROBE_CANDIDATE``.
    """

    agent_version = diagnostic_probe_prompt.DIAGNOSTIC_PROBE_AGENT_VERSION

    def __init__(
        self,
        gateway: LLMGateway,
        *,
        route: ModelRoute = ModelRoute.DIAGNOSTIC_DEFAULT,
        prompts: PromptRegister | None = None,
        mcp_client: RamalsMcpReadClient | None = None,
    ) -> None:
        """Builds the reasoner.

        ``mcp_client`` is the process-shared MCP-3 read client (absent when MCP is not configured).
        It carries no per-interaction state; what is per-interaction is the ``McpExecutionContext``
        a caller supplies to each ``propose`` call. Without it -- MCP not configured, or Java sent
        no delegated context on this request -- there is no governed evidence to reason from and
        ``propose`` returns an envelope that resolves to ``ABSENT``.
        """
        self._gateway = gateway
        self._route = route
        self._prompts = prompts
        self._mcp_client = mcp_client

    def propose(
        self,
        envelope: AIRequestEnvelope,
        *,
        deadline: Deadline,
        mcp_execution_context: McpExecutionContext | None = None,
    ) -> AIProposalEnvelope:
        """Runs one bounded recommendation for the interaction in ``envelope``.

        Never raises for an evidence-acquisition or model failure: every such path returns an
        envelope marked invalid, which Java resolves to ``ABSENT``.
        """
        domain_code = _domain_code(envelope)
        if domain_code is None:
            return self._unavailable(envelope, _NO_DOMAIN)
        if self._mcp_client is None or mcp_execution_context is None:
            return self._unavailable(envelope, _MCP_CONTEXT_MISSING)

        try:
            h6, h7 = self._read_evidence(mcp_execution_context, domain_code)
        except McpError as failure:
            return self._unavailable(envelope, _mcp_reason(failure))

        permitted_misconception_ids = _misconception_ids(h6, h7)
        permitted_evidence_ids = _evidence_ids(h7)
        if (
            h6.diagnostic_data_status != _H6_HAS_EVIDENCE
            or not permitted_misconception_ids
            or not permitted_evidence_ids
        ):
            # Java's orchestrator already gates this, but the reasoner re-checks against its own
            # reads: nothing to reason from means no model call (M2-ADR-032 15).
            return self._unavailable(envelope, _NO_GROUNDABLE_EVIDENCE)

        projection = _project(h6, h7, domain_code, permitted_evidence_ids)
        run = GraphRun(
            self._gateway,
            prompts=self._prompts,
            validator=lambda raw: validate(
                raw,
                frozenset(permitted_evidence_ids),
                frozenset(permitted_misconception_ids),
            ),
            registry=None,
        )
        built = run.build_prompt(
            route=self._route,
            template_id=PromptTemplateId.DIAGNOSTIC_PROBE_CANDIDATE,
            context=projection,
        )
        state = run.build_state(
            agent_type=self.agent_type,
            route=self._route,
            deadline=deadline,
            interaction_id=envelope.interactionId,
            request_id=envelope.requestId,
            proposal_id=envelope.requestId,
            prompt=built,
            minimized_learning_context=projection,
            agent_version=self.agent_version,
            interaction_class=envelope.constraints.interactionClass,
            execution_policy=GatewayExecutionPolicy.SINGLE_SUBMISSION_FAIL_CLOSED,
        )
        return self._to_envelope(run.run(state, route=self._route), domain_code)

    def _read_evidence(
        self, context: McpExecutionContext, domain_code: str
    ) -> tuple[McpDiagnosticReport, McpLongitudinalReport]:
        """The reasoner's own bounded H6 + H7 reads, in one short-lived event loop.

        ``anyio.run`` starts a fresh loop for exactly these two calls and tears it down on return,
        which is correct because ``propose`` is synchronous and, in the deployed app, runs in a
        FastAPI worker thread with no running loop of its own -- the same bridge ``mcp.tools``
        already uses.
        """

        client = self._mcp_client
        if client is None:  # unreachable: propose() checks first. Kept for the type narrowing.
            raise McpError(McpErrorCode.MCP_DISABLED, "no MCP read client")

        async def read_both() -> tuple[McpDiagnosticReport, McpLongitudinalReport]:
            h6 = await client.current_domain_report(context, domain_code=domain_code)
            h7 = await client.longitudinal_summary(context, domain_code=domain_code)
            return h6, h7

        return anyio.run(read_both)

    def _to_envelope(self, state: AgentState, domain_code: str) -> AIProposalEnvelope:
        """Assembles the envelope, carrying provenance through unchanged.

        A run that finished with validation errors still produces an envelope, marked invalid and
        carrying its reason codes -- honest input to a gate, never a verdict about itself.
        ``ACCEPTED`` / ``REJECTED`` are Java's to decide.
        """
        raw = (state.final_proposal or {}).get("text")
        valid = bool(raw) and not state.validation_errors
        payload: dict[str, Any] = (
            self._payload(raw, state, domain_code) if raw and not state.validation_errors else {}
        )
        return AIProposalEnvelope(
            contractVersion=ContractVersion("1.0"),
            proposalId=state.proposal_id,
            agentType=self.agent_type,
            agentVersion=state.agent_version,
            agentRunId=state.agent_run_id,
            promptTemplateId=state.prompt_template_id.value,
            promptVersion=state.prompt_version,
            modelRoute=(state.final_proposal or {}).get("modelRoute", self._route.value),
            resolvedProvider=(state.final_proposal or {}).get("resolvedProvider"),
            modelId=(state.final_proposal or {}).get("modelId"),
            routeVersion=(state.final_proposal or {}).get("routeVersion"),
            providerRequestId=(state.final_proposal or {}).get("providerRequestId"),
            providerMessageId=(state.final_proposal or {}).get("providerMessageId"),
            responseDigest=(state.final_proposal or {}).get("responseDigest"),
            trustLevel=TrustLevel.NON_AUTHORITATIVE,
            reasonCodes=[ReasonCode(code) for code in dict.fromkeys(state.validation_errors)][:16]
            or None,
            proposal=payload,
            validation=Validation(
                schemaValid=valid,
                semanticValid=valid,
                repairAttempts=state.repair_cycle_count,
            ),
            usage=Usage(
                inputTokens=state.input_tokens,
                cachedInputTokens=state.cached_input_tokens,
                outputTokens=state.output_tokens,
                estimatedCostUsd=f"{state.cost_spent_usd:.6f}",
                latencyMs=state.latency_ms,
            ),
        )

    @staticmethod
    def _payload(raw: str, state: AgentState, domain_code: str) -> dict[str, Any]:
        """The validated proposal in contract form, with the runtime's identifiers stamped on.

        The identifiers -- and ``interactionId`` and ``domain`` -- come from the run and the
        request, never from the model: a correlation identity supplied by the thing being
        correlated is not evidence, and Java re-checks both against its own authoritative context
        regardless.
        """
        parsed = json.loads(raw)
        proposal = DiagnosticProbeProposal.model_validate(
            {
                **{key: value for key, value in parsed.items() if key not in _RUNTIME_OWNED},
                "proposalId": state.proposal_id,
                "requestId": state.request_id,
                "agentRunId": state.agent_run_id,
                "interactionId": state.interaction_id,
                "domain": domain_code,
            }
        )
        return proposal.to_contract()

    def _unavailable(self, envelope: AIRequestEnvelope, reason_code: str) -> AIProposalEnvelope:
        """An envelope that carries no recommendation and is marked invalid.

        ``schemaValid=False`` so the internal API returns 422 and Java's client raises
        ``AiUnavailableException`` -> the orchestrator resolves ``ABSENT`` and nothing is persisted.
        No fabricated proposal content (M2-ADR-032 failure semantics).
        """
        return AIProposalEnvelope(
            contractVersion=ContractVersion("1.0"),
            proposalId=envelope.requestId,
            agentType=self.agent_type,
            agentVersion=self.agent_version,
            agentRunId=None,
            promptTemplateId=PromptTemplateId.DIAGNOSTIC_PROBE_CANDIDATE.value,
            promptVersion=None,
            modelRoute=self._route.value,
            trustLevel=TrustLevel.NON_AUTHORITATIVE,
            reasonCodes=[ReasonCode(reason_code)],
            proposal={},
            validation=Validation(schemaValid=False, semanticValid=False, repairAttempts=0),
            usage=Usage(
                inputTokens=0,
                cachedInputTokens=0,
                outputTokens=0,
                estimatedCostUsd="0.000000",
                latencyMs=0,
            ),
        )


def _domain_code(envelope: AIRequestEnvelope) -> str | None:
    """The domain the recommendation concerns, resolved authoritatively by Java and echoed here.

    ``None`` when the request carried no domain context -- there is then no scope for a governed
    H6/H7 read, and the reasoner resolves to ``ABSENT``.
    """
    if envelope.domainContext is None:
        return None
    code = envelope.domainContext.domainCode
    return code if code and code.strip() else None


def _misconception_ids(h6: McpDiagnosticReport, h7: McpLongitudinalReport) -> set[str]:
    """``M_allowed``: every misconception id H6 or H7 surfaced for this learner and domain."""
    ids = {finding.misconception_id for finding in h6.misconception_findings}
    ids.update(finding.misconception_id for finding in h7.findings)
    return ids


def _evidence_ids(h7: McpLongitudinalReport) -> set[str]:
    """``E_allowed``: every post-baseline evidence-observation id H7 exposes.

    The only governed evidence-id set a learner-scoped H6/H7 read makes citable (H6's own
    observation ids are admin-only). A learner with H6 evidence but no H7 baseline therefore has an
    empty ``E_allowed`` and the reasoner resolves to ``ABSENT`` -- a documented limitation, not a
    defect.
    """
    ids: set[str] = set()
    for finding in h7.findings:
        ids.update(finding.later_evidence_observation_ids)
    return ids


def _project(
    h6: McpDiagnosticReport,
    h7: McpLongitudinalReport,
    domain_code: str,
    citable_evidence_ids: set[str],
) -> dict[str, Any]:
    """The bounded view of governed H6/H7 evidence that reaches the prompt.

    Deliberately not the whole reports: no learner identifier, no admin-only observation ids, no
    mastery map. What remains is what a probe recommendation can be grounded in -- the
    misconceptions in scope with their real ``targetNode``, a governed (non-probabilistic) summary
    of their evidence, and the exact set of evidence ids a citation may name.
    """
    h7_by_misconception = {finding.misconception_id: finding for finding in h7.findings}
    misconceptions: list[dict[str, Any]] = []
    seen: set[str] = set()

    for finding in h6.misconception_findings:
        seen.add(finding.misconception_id)
        longitudinal = h7_by_misconception.get(finding.misconception_id)
        misconceptions.append(
            {
                "misconceptionId": finding.misconception_id,
                "name": finding.name,
                "description": finding.description,
                "targetNode": {
                    "kind": finding.target_type,
                    "id": finding.target_id,
                },
                "h6EvidenceSummary": _evidence_summary(finding.evidence_summary),
                "h6ConfidenceState": finding.confidence_state,
                "h6ConfidenceBand": (
                    finding.confidence.band if finding.confidence is not None else None
                ),
                "h7DataStatus": longitudinal.data_status if longitudinal is not None else None,
                "h7State": longitudinal.state if longitudinal is not None else None,
                "h7LaterEvidenceSummary": (
                    _evidence_summary(longitudinal.later_evidence)
                    if longitudinal is not None
                    else None
                ),
            }
        )

    for h7_finding in h7.findings:
        if h7_finding.misconception_id in seen:
            continue
        misconceptions.append(
            {
                "misconceptionId": h7_finding.misconception_id,
                "name": h7_finding.name,
                "description": h7_finding.description,
                "targetNode": {
                    "kind": h7_finding.target_type,
                    "id": h7_finding.target_id,
                },
                "h6EvidenceSummary": None,
                "h6ConfidenceState": None,
                "h6ConfidenceBand": None,
                "h7DataStatus": h7_finding.data_status,
                "h7State": h7_finding.state,
                "h7LaterEvidenceSummary": _evidence_summary(h7_finding.later_evidence),
            }
        )

    return {
        "domain": domain_code,
        "misconceptions": misconceptions,
        "citableEvidenceIds": sorted(citable_evidence_ids),
        "probeIntents": [
            "COLLECT_ADDITIONAL_MISCONCEPTION_EVIDENCE",
            "DISCRIMINATE_BETWEEN_EVIDENCE_STATES",
        ],
    }


def _evidence_summary(summary: Any) -> dict[str, int]:
    """The governed supporting / contradictory / inconclusive counts, nothing derived from them."""
    return {
        "supporting": summary.supporting_count,
        "contradictory": summary.contradictory_count,
        "inconclusive": summary.inconclusive_count,
    }


def _mcp_reason(failure: McpError) -> str:
    """A bounded reason code for an MCP read failure, safe to record (no leaked detail)."""
    return f"{_MCP_UNAVAILABLE}_{failure.code.value}"[:64]
