"""MVP-2 Assessment Evaluation Agent (M2-T11)."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Any

from ramals_ai.assessment_evaluation import prompt
from ramals_ai.assessment_evaluation.contracts import AssessmentEvaluationProposal
from ramals_ai.assessment_evaluation.validation import require_evaluation_request, validate
from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import (
    AgentType,
    AIProposalEnvelope,
    AssessmentEvaluationContext,
    AssessmentEvaluationRequest,
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
from ramals_ai.grounding.contracts import ContextAuthority, GroundedContext
from ramals_ai.prompting.templates import PromptRegister, PromptTemplateId


class AssessmentEvaluationAgent:
    """Produces a grounded rubric proposal and has no domain-write capability."""

    agent_type = AgentType.ASSESSMENT
    agent_version = prompt.ASSESSMENT_EVALUATION_AGENT_VERSION

    def __init__(
        self,
        gateway: LLMGateway,
        *,
        route: ModelRoute = ModelRoute.ASSESSMENT_DEFAULT,
        prompts: PromptRegister | None = None,
    ) -> None:
        self._gateway = gateway
        self._route = route
        self._prompts = prompts

    def propose(
        self,
        request: AssessmentEvaluationRequest,
        context: GroundedContext,
        *,
        deadline: Deadline,
    ) -> AIProposalEnvelope:
        """Run one bounded evaluation after fail-closed context binding."""
        require_evaluation_request(request, context)
        projection = _project(request.evaluationContext, context)
        run = GraphRun(
            self._gateway,
            prompts=self._prompts,
            validator=lambda raw: validate(raw, request.evaluationContext, context),
        )
        built = run.build_prompt(
            route=self._route,
            template_id=PromptTemplateId.ASSESSMENT_RUBRIC_EVALUATE,
            context=projection,
        )
        state = run.build_state(
            agent_type=self.agent_type,
            route=self._route,
            deadline=deadline,
            interaction_id=request.interactionId,
            request_id=request.requestId,
            proposal_id=request.requestId,
            prompt=built,
            minimized_learning_context=projection,
            policy_constraints={
                "contextId": context.contextId,
                "answerVersion": request.evaluationContext.answerVersion,
                "rubricVersion": request.evaluationContext.rubricVersion,
            },
            agent_version=self.agent_version,
            interaction_class=request.constraints.interactionClass,
        )
        return self._to_envelope(run.run(state, route=self._route), request.evaluationContext)

    def _to_envelope(
        self, state: AgentState, evaluation: AssessmentEvaluationContext
    ) -> AIProposalEnvelope:
        raw = (state.final_proposal or {}).get("text")
        payload = (
            self._payload(raw, state, evaluation)
            if raw and not state.validation_errors
            else {
                "answerVersion": evaluation.answerVersion,
                "rubricVersion": evaluation.rubricVersion,
                "dimensions": [],
                "feedback": "",
                "evidenceIds": [],
                "confidence": 0.0,
            }
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
    def _payload(
        raw: str, state: AgentState, evaluation: AssessmentEvaluationContext
    ) -> dict[str, Any]:
        parsed = json.loads(raw)
        proposal = AssessmentEvaluationProposal.model_validate(
            {
                **{key: value for key, value in parsed.items() if key not in _RUNTIME_OWNED},
                "contractVersion": "1.0",
                "proposalId": state.proposal_id,
                "requestId": state.request_id,
                "agentRunId": state.agent_run_id,
                "answerVersion": evaluation.answerVersion,
                "rubricVersion": evaluation.rubricVersion,
            }
        )
        return proposal.to_contract()


_RUNTIME_OWNED = frozenset(
    {
        "contractVersion",
        "proposalId",
        "requestId",
        "agentRunId",
        "answerVersion",
        "rubricVersion",
    }
)


def _project(evaluation: AssessmentEvaluationContext, context: GroundedContext) -> dict[str, Any]:
    """Bounded prompt view; learnerRef and model-generated summaries never cross."""
    now = datetime.now(UTC)
    return {
        "contextId": context.contextId,
        "retrievalPolicyVersion": context.retrievalPolicyVersion,
        "evaluation": evaluation.model_dump(mode="json"),
        "supportingFacts": [
            {
                "evidenceId": item.evidenceId,
                "sourceType": item.sourceType.value,
                "sourceVersion": item.sourceVersion,
                "factType": item.factType,
                "value": item.value,
                "observedAt": item.observedAt.isoformat(),
            }
            for item in context.items
            if item.authority is ContextAuthority.AUTHORITATIVE_FACT
            and (item.expiresAt is None or item.expiresAt > now)
        ],
    }
