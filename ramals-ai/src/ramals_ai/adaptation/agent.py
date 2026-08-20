"""Adaptation Agent V1 adapter for the bounded internal HTTP boundary."""

from __future__ import annotations

import json
from typing import Any

from ramals_ai.adaptation import prompt
from ramals_ai.adaptation.minimizer import minimize
from ramals_ai.adaptation.validation import validate
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
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.graph.runtime import GraphRun
from ramals_ai.graph.state import AgentState
from ramals_ai.prompting.templates import PromptRegister, PromptTemplateId


class AdaptationAgent:
    """Produces a candidate next action; deterministic Spring policy remains authoritative."""

    agent_type = AgentType.ADAPTATION
    agent_version = prompt.ADAPTATION_AGENT_VERSION

    def __init__(
        self,
        gateway: LLMGateway,
        *,
        route: ModelRoute = ModelRoute.ADAPTATION_DEFAULT,
        prompts: PromptRegister | None = None,
    ) -> None:
        """Builds the agent.

        ``prompts`` is injectable so the process serves the register it validated at startup rather
        than assembling a second one here. They are the same object today; the parameter is what
        keeps them the same object after someone adds a revision.
        """
        self._gateway = gateway
        self._route = route
        self._prompts = prompts

    def propose(self, envelope: AIRequestEnvelope, *, deadline: Deadline) -> AIProposalEnvelope:
        context = minimize(envelope)
        run = GraphRun(
            self._gateway,
            prompts=self._prompts,
            validator=lambda raw: validate(raw, dict(context)),
        )
        built = run.build_prompt(
            route=self._route,
            template_id=PromptTemplateId.ADAPTATION_PLAN,
            context=context,
        )
        state = run.build_state(
            agent_type=self.agent_type,
            route=self._route,
            deadline=deadline,
            interaction_id=envelope.interactionId,
            request_id=envelope.requestId,
            proposal_id=envelope.requestId,
            prompt=built,
            minimized_learning_context=dict(context),
            agent_version=self.agent_version,
            interaction_class=envelope.constraints.interactionClass,
        )
        return self._to_proposal(run.run(state, route=self._route))

    def _to_proposal(self, state: AgentState) -> AIProposalEnvelope:
        raw = (state.final_proposal or {}).get("text")
        payload = (
            self._parse(raw)
            if raw and not state.validation_errors
            else {
                "skillCode": None,
                "recommendedAction": None,
                "rationale": "",
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
            return {"skillCode": None, "recommendedAction": None, "rationale": ""}
        return parsed if isinstance(parsed, dict) else {}
