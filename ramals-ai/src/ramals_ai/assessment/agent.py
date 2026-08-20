"""Assessment Agent V1 — candidate content, and formative help that is never a score.

Two operations, deliberately separate methods rather than one with a mode flag, because they carry
different authority and a flag is a thing that can be passed wrongly:

``propose`` writes a candidate item. It returns ``UNVERIFIED`` — the trust state M1-ADR-006 gives
everything a generator produces. Nothing in this class can return a verified state; there is no
parameter for it and no code path to it.

``evaluate`` returns formative material. It returns ``FORMATIVE_ONLY`` — never a score, a mark, a
mastery level or a progression decision (M1-ADR-010). That guarantee does not rest on this file: the
AI plane holds no database credential at all (M1-T03) and ``ramals_ai_runtime`` has no privilege on
``ledger`` (V015), so the strongest form of the rule is enforced where a mistake here could not
reach it.

Every proposal carries provenance — which agent, which prompt version, which model route wrote it.
An item outlives the request that produced it and will be read months later by a reviewer asking
whether a whole batch needs re-checking after a prompt change. Provenance is embedded in the payload
rather than left in the envelope alone, so the answer survives the item being stored on its own.
"""

from __future__ import annotations

import json
import logging
from typing import Any

from ramals_ai.assessment import prompt as assessment_prompt
from ramals_ai.assessment.evaluation import validate_evaluation
from ramals_ai.assessment.minimizer import minimize
from ramals_ai.assessment.validation import validate_item
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
from ramals_ai.telemetry.logging import business_event

logger = logging.getLogger(__name__)

PROPOSED_CONTENT_TRUST_LEVEL = TrustLevel.UNVERIFIED
"""Generated content is UNVERIFIED. M1-ADR-006 makes promotion a separate, human act."""

EVALUATION_TRUST_LEVEL = TrustLevel.FORMATIVE_ONLY
"""AI evaluation is FORMATIVE_ONLY in MVP-1. M1-ADR-010 admits no exception."""

_EMPTY_ITEM: dict[str, Any] = {
    "skillCode": None,
    "objectiveCode": None,
    "difficulty": None,
    "stem": "",
    "options": [],
    "answerKey": [],
    "rationale": "",
}

_EMPTY_EVALUATION: dict[str, Any] = {
    "skillCode": None,
    "indicators": {},
    "misconceptions": [],
    "suggestedProbe": "",
}


class AssessmentAgent:
    """Produces one candidate item, or one piece of formative material, for one skill."""

    agent_type = AgentType.ASSESSMENT
    agent_version = assessment_prompt.ASSESSMENT_AGENT_VERSION

    def __init__(
        self,
        gateway: LLMGateway,
        *,
        route: ModelRoute = ModelRoute.ASSESSMENT_DEFAULT,
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

    def propose(
        self,
        envelope: AIRequestEnvelope,
        *,
        deadline: Deadline,
        requested_difficulty: str,
        objectives: tuple[str, ...] = (),
    ) -> AIProposalEnvelope:
        """Writes one candidate item. The result is UNVERIFIED and reaches no learner unpromoted.

        ``requested_difficulty`` is the band Spring decided to commission, derived there from the
        mastery state Spring owns. The agent receives that decision and not the learner-specific
        status it came from — see :mod:`ramals_ai.assessment.minimizer` for why the distinction
        matters to an artefact that outlives the request.

        A caller with no band should not be commissioning an item, so this is required rather than
        defaulted.
        """
        context: dict[str, Any] = dict(minimize(envelope))
        context["requestedDifficulty"] = requested_difficulty
        if objectives:
            # Curriculum facts Spring supplies, not anything derived from a learner, which is why
            # they are added after minimization rather than allowlisted through it.
            context["availableObjectives"] = list(objectives)

        state = self._run(
            envelope,
            deadline,
            PromptTemplateId.ASSESSMENT_ITEM,
            {
                "context": context,
                "requested_difficulty": requested_difficulty,
                "objectives": objectives,
            },
            lambda raw: validate_item(raw, context),
        )
        return self._to_proposal(
            state,
            trust_level=PROPOSED_CONTENT_TRUST_LEVEL,
            operation="assessment.propose",
            empty=_EMPTY_ITEM,
        )

    def evaluate(self, envelope: AIRequestEnvelope, *, deadline: Deadline) -> AIProposalEnvelope:
        """Produces formative material. Never a score, a mark or a mastery decision."""
        context: dict[str, Any] = dict(minimize(envelope))
        state = self._run(
            envelope,
            deadline,
            # A different template, not a different version of the same one. Generating an item and
            # evaluating a response fail in different ways, so a recorded identity has to say which
            # of the two produced the output.
            PromptTemplateId.ASSESSMENT_EVALUATE,
            {"context": context},
            lambda raw: validate_evaluation(raw, context),
        )
        return self._to_proposal(
            state,
            trust_level=EVALUATION_TRUST_LEVEL,
            operation="assessment.evaluate",
            empty=_EMPTY_EVALUATION,
        )

    def _run(
        self,
        envelope: AIRequestEnvelope,
        deadline: Deadline,
        template_id: PromptTemplateId,
        prompt_arguments: dict[str, Any],
        validator: Any,
    ) -> AgentState:
        run = GraphRun(self._gateway, prompts=self._prompts, validator=validator)
        prompt = run.build_prompt(route=self._route, template_id=template_id, **prompt_arguments)
        state = run.build_state(
            agent_type=self.agent_type,
            route=self._route,
            deadline=deadline,
            interaction_id=envelope.interactionId,
            request_id=envelope.requestId,
            proposal_id=envelope.requestId,
            prompt=prompt,
            minimized_learning_context={},
            agent_version=self.agent_version,
            interaction_class=envelope.constraints.interactionClass,
        )
        return run.run(state, route=self._route)

    def _to_proposal(
        self,
        state: AgentState,
        *,
        trust_level: TrustLevel,
        operation: str,
        empty: dict[str, Any],
    ) -> AIProposalEnvelope:
        model_route = (state.final_proposal or {}).get("modelRoute", self._route.value)
        payload = self._parse(state, empty)
        payload["provenance"] = {
            "agentType": self.agent_type.value,
            "agentVersion": state.agent_version,
            # Both of this agent's templates share a version, so the version alone cannot say which
            # prompt produced the item -- generating one and evaluating a response are different
            # instructions with different failure modes.
            "promptTemplateId": state.prompt_template_id.value,
            "promptVersion": state.prompt_version,
            "modelRoute": model_route,
            "trustLevel": trust_level.value,
        }

        business_event(
            logger,
            level=logging.INFO,
            operation=operation,
            message="assessment proposal produced",
            fields={
                "trustLevel": trust_level.value,
                "promptVersion": state.prompt_version,
                "validationErrors": len(state.validation_errors),
                "outcome": "SUCCESS",
            },
        )

        return AIProposalEnvelope(
            contractVersion=ContractVersion("1.0"),
            proposalId=state.proposal_id,
            agentType=self.agent_type,
            agentVersion=state.agent_version,
            agentRunId=state.agent_run_id,
            promptTemplateId=state.prompt_template_id.value,
            promptVersion=state.prompt_version,
            modelRoute=model_route,
            # Stated on the wire, on every proposal, whatever the content. A candidate that arrived
            # looking finished is still a candidate.
            trustLevel=trust_level,
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
    def _parse(state: AgentState, empty: dict[str, Any]) -> dict[str, Any]:
        """Returns the model's payload, or an explicitly empty one when it never validated.

        An unusable output becomes an empty payload carrying its reason codes rather than raw model
        text. Passing the text through would hand Spring something that looks like an item and is
        not one — and Spring's next act is to store it.
        """
        raw = (state.final_proposal or {}).get("text")
        if not raw or state.validation_errors:
            return dict(empty)
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError, TypeError:
            return dict(empty)
        return parsed if isinstance(parsed, dict) else dict(empty)
