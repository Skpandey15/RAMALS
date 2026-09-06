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
from ramals_ai.graph.tools import ToolRegistry
from ramals_ai.mcp.client import RamalsMcpReadClient
from ramals_ai.mcp.context import McpExecutionContext
from ramals_ai.mcp.tools import DIAGNOSTIC_AGENT_CAPABILITIES, build_mcp_tool_registry
from ramals_ai.prompting.templates import PromptRegister, PromptTemplateId


class DiagnosticAgent:
    """Produces one non-authoritative diagnostic probe proposal."""

    agent_type = AgentType.DIAGNOSTIC
    agent_version = diagnostic_prompt.DIAGNOSTIC_AGENT_VERSION

    def __init__(
        self,
        gateway: LLMGateway,
        *,
        route: ModelRoute = ModelRoute.DIAGNOSTIC_DEFAULT,
        prompts: PromptRegister | None = None,
        mcp_client: RamalsMcpReadClient | None = None,
    ) -> None:
        """Builds the agent.

        ``prompts`` is injectable so the process serves the register it validated at startup rather
        than assembling a second one here. They are the same object today; the parameter is what
        keeps them the same object after someone adds a revision.

        ``mcp_client`` is the process-shared MCP-3 read client (absent when MCP is not configured,
        the same "off means safely off" shape ``durable_execution_enabled`` already holds this
        service to). It carries no per-interaction state itself, so sharing it across every
        ``propose`` call is safe; what is per-interaction is the ``McpExecutionContext`` a caller
        supplies to each call, never this constructor.
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
        context = self._context(envelope)
        run = GraphRun(
            self._gateway,
            prompts=self._prompts,
            validator=lambda raw: validate(raw, context),
            registry=self._mcp_registry(mcp_execution_context),
        )
        prompt = run.build_prompt(
            route=self._route,
            template_id=PromptTemplateId.DIAGNOSTIC_ROOT_CAUSE,
            context=context,
        )
        state = run.build_state(
            agent_type=self.agent_type,
            route=self._route,
            deadline=deadline,
            interaction_id=envelope.interactionId,
            request_id=envelope.requestId,
            proposal_id=envelope.requestId,
            prompt=prompt,
            minimized_learning_context=context,
            agent_version=self.agent_version,
            interaction_class=envelope.constraints.interactionClass,
        )
        return self._to_proposal(run.run(state, route=self._route))

    def _mcp_registry(self, context: McpExecutionContext | None) -> ToolRegistry | None:
        """A fresh, interaction-scoped registry granting exactly this agent's MCP-3 reads, or
        ``None`` (``GraphRun`` then falls back to its own empty registry -- exactly today's
        existing, no-tools behavior).

        ``None`` whenever either half is missing: no shared client (MCP not configured for this
        process) or no per-interaction context (Java did not attach a delegated learner-context
        credential to this call). Absence is a safe degrade, never an error -- a run with no MCP
        evidence available still proposes; it just proposes without it, as it always has. Built
        fresh on every call, scoped to exactly ``DIAGNOSTIC_AGENT_CAPABILITIES`` for this agent type
        alone, never the shared multi-agent default -- and never held past this one call.
        """
        if self._mcp_client is None or context is None:
            return None
        return build_mcp_tool_registry(
            self._mcp_client,
            context,
            allowlists={AgentType.DIAGNOSTIC: DIAGNOSTIC_AGENT_CAPABILITIES},
        )

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
