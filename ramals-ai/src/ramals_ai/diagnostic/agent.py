"""Diagnostic Agent V1 adapter for the internal HTTP boundary."""

from __future__ import annotations

import json
from typing import Any

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
from ramals_ai.diagnostic import prompt as diagnostic_prompt
from ramals_ai.diagnostic.validation import validate
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.graph.runtime import GraphRun
from ramals_ai.graph.state import AgentState


class DiagnosticAgent:
    """Produces one non-authoritative diagnostic probe proposal."""

    agent_type = AgentType.DIAGNOSTIC
    agent_version = diagnostic_prompt.DIAGNOSTIC_AGENT_VERSION

    def __init__(
        self, gateway: LLMGateway, *, route: ModelRoute = ModelRoute.DIAGNOSTIC_DEFAULT
    ) -> None:
        self._gateway = gateway
        self._route = route

    def propose(self, envelope: AIRequestEnvelope, *, deadline: Deadline) -> AIProposalEnvelope:
        context = self._context(envelope)
        messages = diagnostic_prompt.build_messages(context)
        run = GraphRun(self._gateway, validator=lambda raw: validate(raw, context))
        state = run.build_state(
            agent_type=self.agent_type,
            route=self._route,
            deadline=deadline,
            interaction_id=envelope.interactionId,
            request_id=envelope.requestId,
            proposal_id=envelope.requestId,
            minimized_learning_context=context,
            agent_version=self.agent_version,
            interaction_class=envelope.constraints.interactionClass,
        )
        return self._to_proposal(run.run(state, route=self._route, messages=messages))

    @staticmethod
    def _context(envelope: AIRequestEnvelope) -> dict[str, Any]:
        context: dict[str, Any] = {}
        if envelope.learningContext is not None:
            context.update(envelope.learningContext.model_dump(mode="json", exclude_none=True))
        if envelope.domainContext is not None:
            context["domainContext"] = envelope.domainContext.model_dump(
                mode="json", exclude_none=True
            )
        if envelope.learningGoalContext is not None:
            context["learningGoalContext"] = envelope.learningGoalContext.model_dump(
                mode="json", exclude_none=True
            )
        return context

    def _to_proposal(self, state: AgentState) -> AIProposalEnvelope:
        raw = (state.final_proposal or {}).get("text")
        payload = (
            self._parse(raw)
            if raw and not state.validation_errors
            else {
                "skillCode": None,
                "objectiveCode": None,
                "difficulty": None,
                "rationale": "",
                "inferredStatus": None,
            }
        )
        return AIProposalEnvelope(
            contractVersion=ContractVersion("1.0"),
            proposalId=state.proposal_id,
            agentType=self.agent_type,
            agentVersion=state.agent_version,
            promptVersion=state.prompt_version,
            modelRoute=(state.final_proposal or {}).get("modelRoute", self._route.value),
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
    def _parse(raw: str) -> dict[str, Any]:
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError, TypeError:
            return {
                "skillCode": None,
                "objectiveCode": None,
                "difficulty": None,
                "rationale": "",
                "inferredStatus": None,
            }
        return parsed if isinstance(parsed, dict) else {}
