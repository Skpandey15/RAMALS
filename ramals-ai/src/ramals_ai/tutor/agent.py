"""Tutor Agent V1 — the first low-authority personalized agent.

It runs the bounded graph from M1-T06 over the governed gateway from M1-T05, and returns an
``AIProposalEnvelope`` marked ``NON_AUTHORITATIVE``. That marking is not a courtesy: the platform's
deterministic engines decide what a learner has mastered, and this agent's output changes nothing on
its own. It cannot write mastery, evidence or progression because it has no path to them — the AI
plane holds no database credential at all (M1-T03), which is a stronger guarantee than any flag.

The interesting decisions are upstream of the model. Context is minimized to an allowlist before a
prompt exists, so an answer key cannot be leaked from a process that never held one. Output is
validated for fabricated learner-state claims, because a sentence like "you struggled with this last
week" reads as attentive personalization and is indistinguishable, to the learner, from something
the platform actually knows.
"""

from __future__ import annotations

import json
import logging
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
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.graph.runtime import GraphRun
from ramals_ai.graph.state import AgentState
from ramals_ai.tutor import prompt as tutor_prompt
from ramals_ai.tutor.minimizer import minimize
from ramals_ai.tutor.validation import validate

logger = logging.getLogger(__name__)


class TutorAgent:
    """Produces one tutor proposal for one skill."""

    agent_type = AgentType.TUTOR
    agent_version = tutor_prompt.TUTOR_AGENT_VERSION

    def __init__(
        self, gateway: LLMGateway, *, route: ModelRoute = ModelRoute.TUTOR_DEFAULT
    ) -> None:
        self._gateway = gateway
        self._route = route

    def respond(self, envelope: AIRequestEnvelope, *, deadline: Deadline) -> AIProposalEnvelope:
        """Runs the bounded graph and returns a non-authoritative proposal."""
        context = minimize(envelope)
        messages = tutor_prompt.build_messages(context, envelope.requestedCapability)

        run = GraphRun(self._gateway, validator=lambda raw: validate(raw, context))
        state = run.build_state(
            agent_type=self.agent_type,
            route=self._route,
            deadline=deadline,
            interaction_id=envelope.interactionId,
            request_id=envelope.requestId,
            proposal_id=envelope.requestId,
            minimized_learning_context=dict(context),
            agent_version=self.agent_version,
        )

        result = run.run(state, route=self._route, messages=messages)
        return self._to_proposal(result)

    def _to_proposal(self, state: AgentState) -> AIProposalEnvelope:
        payload = self._parse(state)

        return AIProposalEnvelope(
            contractVersion=ContractVersion("1.0"),
            proposalId=state.proposal_id,
            agentType=self.agent_type,
            agentVersion=state.agent_version,
            promptVersion=state.prompt_version,
            modelRoute=(state.final_proposal or {}).get("modelRoute", self._route.value),
            # Stated on the wire, on every proposal, whatever the content. A proposal that arrived
            # looking confident is still a proposal.
            trustLevel=TrustLevel.NON_AUTHORITATIVE,
            reasonCodes=[ReasonCode(code) for code in dict.fromkeys(state.validation_errors)][:16]
            or None,
            proposal=payload,
            validation=Validation(
                schemaValid=not state.validation_errors,
                semanticValid=not state.validation_errors,
                repairAttempts=state.repair_count,
            ),
            usage=Usage(
                inputTokens=None,
                outputTokens=None,
                estimatedCostUsd=f"{state.cost_spent_usd:.6f}",
                latencyMs=None,
            ),
        )

    @staticmethod
    def _parse(state: AgentState) -> dict[str, Any]:
        """Returns the model's payload, or an explicitly empty one when it never validated.

        An unusable output becomes an empty proposal carrying its reason codes rather than raw
        model text. Passing the text through would hand the caller something that looks like a
        tutor answer and is not one -- and the caller's job is to render this to a learner.
        """
        raw = (state.final_proposal or {}).get("text")
        if not raw or state.validation_errors:
            return {"responseType": "NONE", "explanation": "", "checksForUnderstanding": []}
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError, TypeError:
            return {"responseType": "NONE", "explanation": "", "checksForUnderstanding": []}
        return parsed if isinstance(parsed, dict) else {}
