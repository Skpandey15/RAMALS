"""Diagnostic Assessment Agent V1 (M2-T08).

Reads a Spring-built `GroundedContext` and proposes a skill-by-skill reading of what the evidence
shows. Proposal-only, in the strong sense: it computes no mastery, writes nothing, and holds no
reference to anything that could write. `recommendedNextSkills` are strings in a payload.

The agent runs on the existing bounded graph rather than a parallel one. Its state, prompt,
validation and contract are its own, per M2-T08; its ceilings, deadline, budget, routing and
provenance are the platform's, and reusing them is what keeps a second agent from acquiring a second
set of limits that drift from the first.
"""

from __future__ import annotations

import json
from typing import Any

from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import (
    AgentType,
    AIProposalEnvelope,
    ContractVersion,
    InteractionClass,
    ReasonCode,
    TrustLevel,
    Usage,
    Validation,
)
from ramals_ai.diagnostic_assessment import prompt
from ramals_ai.diagnostic_assessment.validation import validate
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.graph.runtime import GraphRun
from ramals_ai.graph.state import AgentState
from ramals_ai.grounding.contracts import ContextAuthority, GroundedContext, SourceType
from ramals_ai.prompting.templates import PromptRegister, PromptTemplateId

REQUIRED_SOURCES: frozenset[SourceType] = frozenset(
    {SourceType.MASTERY, SourceType.LEARNER_EVIDENCE}
)
"""Diagnosis without recorded evidence, or without the mastery it is read against, is guesswork.

Required rather than preferred: `require_grounding` refuses the run before any model call, so a
context missing either costs nothing and produces nothing.
"""


class DiagnosticAssessmentAgent:
    """Proposes a grounded reading of learner evidence; Spring decides what it means."""

    agent_type = AgentType.DIAGNOSTIC
    """The MVP-1 agent type, reused deliberately.

    `AgentType` is generated from the OpenAPI contract, and inventing a member here would be a
    transport-contract change made by an agent implementation. The platform already distinguishes
    two prompts within one agent type by `promptTemplateId` -- that is why the field exists -- so a
    recorded execution says `DIAGNOSTIC` + `DIAGNOSTIC_ASSESSMENT` and is unambiguous.
    """

    agent_version = prompt.DIAGNOSTIC_ASSESSMENT_AGENT_VERSION

    def __init__(
        self,
        gateway: LLMGateway,
        *,
        route: ModelRoute = ModelRoute.DIAGNOSTIC_DEFAULT,
        prompts: PromptRegister | None = None,
    ) -> None:
        self._gateway = gateway
        self._route = route
        self._prompts = prompts

    def propose(
        self,
        context: GroundedContext,
        *,
        interaction_id: str,
        request_id: str,
        deadline: Deadline,
        interaction_class: InteractionClass = InteractionClass.INTERACTIVE_AI,
    ) -> AIProposalEnvelope:
        """Runs one bounded graph execution over the supplied context.

        Raises before any model call when the context is stale or missing a required source: the
        grounded-context contract fails closed, and spending a provider call to discover that would
        be spending money to reach the same answer.
        """
        context.require_grounding(set(REQUIRED_SOURCES))

        permitted_evidence_ids = _authoritative_evidence_ids(context)
        permitted_skill_codes = _skill_codes(context)
        projection = _project(context)

        run = GraphRun(
            self._gateway,
            prompts=self._prompts,
            validator=lambda raw: validate(
                raw,
                permitted_evidence_ids,
                permitted_skill_codes=permitted_skill_codes,
            ),
        )
        built = run.build_prompt(
            route=self._route,
            template_id=PromptTemplateId.DIAGNOSTIC_ASSESSMENT,
            context=projection,
        )
        state = run.build_state(
            agent_type=self.agent_type,
            route=self._route,
            deadline=deadline,
            interaction_id=interaction_id,
            request_id=request_id,
            proposal_id=request_id,
            prompt=built,
            minimized_learning_context=projection,
            policy_constraints={"contextId": context.contextId},
            agent_version=self.agent_version,
            interaction_class=interaction_class,
        )
        return self._to_envelope(run.run(state, route=self._route))

    def _to_envelope(self, state: AgentState) -> AIProposalEnvelope:
        """Assembles the envelope, carrying provenance v2 through unchanged.

        A run that finished with validation errors still produces an envelope, marked invalid and
        carrying its reason codes. That is not the agent asserting a verdict about itself:
        `ACCEPTED` and `REJECTED` are Spring's to decide, and an envelope saying "here is what came
        back, and here is what was wrong with it" is the honest thing to hand a gate.
        """
        raw = (state.final_proposal or {}).get("text")
        payload = (
            self._payload(raw, state)
            if raw and not state.validation_errors
            else {"diagnoses": [], "recommendedNextSkills": [], "confidence": 0.0}
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
            trustLevel=TrustLevel.NON_AUTHORITATIVE,
            reasonCodes=[ReasonCode(code) for code in dict.fromkeys(state.validation_errors)][:16]
            or None,
            proposal=payload,
            validation=Validation(
                schemaValid=not state.validation_errors,
                semanticValid=not state.validation_errors,
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
    def _payload(raw: str, state: AgentState) -> dict[str, Any]:
        """The validated proposal in contract form, with the platform's identifiers stamped on.

        The identifiers come from the run rather than from the model. A correlation identity
        supplied by the thing being correlated is not evidence of anything.
        """
        from ramals_ai.diagnostic_assessment.contracts import DiagnosticAssessmentProposal

        parsed = json.loads(raw)
        proposal = DiagnosticAssessmentProposal.model_validate(
            {
                **{key: value for key, value in parsed.items() if key not in _RUNTIME_OWNED},
                "proposalId": state.proposal_id,
                "requestId": state.request_id,
                "agentRunId": state.agent_run_id,
            }
        )
        return proposal.to_contract()


_RUNTIME_OWNED = frozenset({"contractVersion", "proposalId", "requestId", "agentRunId"})


def _authoritative_evidence_ids(context: GroundedContext) -> frozenset[str]:
    """The identifiers a claim may cite.

    Model-generated summaries are excluded on purpose. A summary is something an earlier model said,
    and letting a diagnosis rest on one would let two model outputs bootstrap each other into
    looking like evidence.
    """
    return frozenset(
        item.evidenceId
        for item in context.items
        if item.authority is ContextAuthority.AUTHORITATIVE_FACT
    )


def _skill_codes(context: GroundedContext) -> frozenset[str] | None:
    """Skill codes the context actually mentions, or ``None`` when it names none.

    ``None`` rather than an empty set, because the two mean opposite things: an empty set would
    reject every classification, while a context carrying no skill-graph facts simply cannot answer
    the question and the evidence check remains the binding one.
    """
    codes = {
        str(item.value)
        for item in context.items
        if item.sourceType in (SourceType.SKILL_GRAPH, SourceType.MASTERY)
        and item.factType.upper().endswith("SKILL_CODE")
    }
    return frozenset(codes) or None


def _project(context: GroundedContext) -> dict[str, Any]:
    """The bounded view of the context that reaches the prompt.

    Deliberately not the whole object: `learnerRef` and the freshness window are the platform's
    concern and say nothing useful to a model reading evidence. What remains is what a claim can be
    grounded in, with the authority of each item stated so the model can tell a recorded fact from
    an earlier model's summary.
    """
    return {
        "contextId": context.contextId,
        "retrievalPolicyVersion": context.retrievalPolicyVersion,
        "asOf": context.asOf.isoformat(),
        "items": [
            {
                "evidenceId": item.evidenceId,
                "sourceType": item.sourceType.value,
                "sourceVersion": item.sourceVersion,
                "authority": item.authority.value,
                "factType": item.factType,
                "value": item.value,
                "observedAt": item.observedAt.isoformat(),
            }
            for item in context.items
        ],
    }
