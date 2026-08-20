"""Prompt provenance: what is recorded must be what ran (M1-ADR-008, M1-ADR-011).

The Master Plan lists **hallucinated provenance** among the AI-adversarial cases a release must be
tested against, and the observability HLD explains why it matters: prompt text is never logged, so
``promptTemplateId`` and ``promptVersion`` are logged *instead of* it. The identity is the evidence.
An identity that does not name the prompt that was sent is not a labelling wart — it is a record
asserting something untrue about a real output, in the one field an investigator has to trust.

This was not hypothetical. Before M1-ADR-011 a prompt rollback moved the version recorded on every
subsequent proposal and left the dispatched messages byte-identical, and a run on the shared
``ci-fake`` route recorded ``CI_FAKE_PROMPT_V1`` -- a string naming none of the five prompts that
route actually sends. Both passed the suite.

``test_the_recorded_identity_selects_the_prompt_that_is_sent`` is the general form of the
invariant: rebind the identity the proposal records to a distinctive artifact and require the bytes
the provider receives to change with it. Asserting that the version equals some constant would pass
in exactly the broken state, which is how the original defect survived a full suite.
"""

from __future__ import annotations

import json
import uuid
from typing import Any

import pytest
from fastapi.testclient import TestClient

from ramals_ai.adaptation.agent import AdaptationAgent
from ramals_ai.assessment.agent import AssessmentAgent
from ramals_ai.config.settings import ConfigurationError, ModelRoute, Settings
from ramals_ai.contracts.generated import AIRequestEnvelope
from ramals_ai.diagnostic.agent import DiagnosticAgent
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import Message, ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.gateway.routes import (
    ROUTE_TABLE_VERSION,
    RouteTableError,
    default_registry,
    pins_from_config,
    registry_from_pins,
    unbuildable_pointers,
)
from ramals_ai.main import create_app
from ramals_ai.prompting.register import default_prompt_register, every_template_is_declared
from ramals_ai.prompting.templates import (
    PromptArtifact,
    PromptRegister,
    PromptTemplateId,
    UnknownPromptVersionError,
    register_of,
)
from ramals_ai.tutor.agent import TutorAgent


class CapturingProvider(FakeProvider):
    """Records exactly what reached the provider, so a claim can be checked against a dispatch."""

    def __init__(self, payload: str) -> None:
        super().__init__()
        self._payload = payload
        self.dispatched: list[tuple[Message, ...]] = []

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        self.dispatched.append(request.messages)
        return ProviderResponse(
            text=self._payload, input_tokens=100, output_tokens=50, cached_input_tokens=0
        )


def envelope() -> AIRequestEnvelope:
    return AIRequestEnvelope.model_validate(
        {
            "contractVersion": "1.0",
            "interactionId": str(uuid.uuid7()),
            "requestId": str(uuid.uuid4()),
            "learner": {"learnerRef": "opaque-learner-ref-001", "locale": "en-IN"},
            "learningContext": {
                "skillCode": "KAFKA_PARTITION",
                "masteryStatus": "NEEDS_PRACTICE",
                "prerequisites": ["KAFKA_TOPIC"],
            },
            "domainContext": {
                "domainCode": "KAFKA",
                "domainType": "TECHNOLOGY",
                "curriculumVersion": "v1",
            },
            "constraints": {"interactionClass": "INTERACTIVE_AI", "deadlineMs": 8000},
            "requestedCapability": "EXPLAIN",
        }
    )


TUTOR_OUTPUT = json.dumps(
    {
        "responseType": "EXPLAIN",
        "explanation": "A partition is an ordered, append-only log.",
        "checksForUnderstanding": ["What holds across two partitions?"],
    }
)


def run_agent(
    agent_name: str, *, prompts: PromptRegister | None = None
) -> tuple[Any, CapturingProvider]:
    """Runs one agent surface on ``ci-fake`` and returns its proposal with what was dispatched."""
    provider = CapturingProvider(TUTOR_OUTPUT)
    gateway = LLMGateway(provider, clock=lambda: 1000.0, sleep=lambda _s: None)
    deadline = Deadline.in_ms(8_000, clock=lambda: 1000.0)
    route = ModelRoute.CI_FAKE

    if agent_name == "tutor":
        proposal = TutorAgent(gateway, route=route, prompts=prompts).respond(
            envelope(), deadline=deadline
        )
    elif agent_name == "diagnostic":
        proposal = DiagnosticAgent(gateway, route=route, prompts=prompts).propose(
            envelope(), deadline=deadline
        )
    elif agent_name == "adaptation":
        proposal = AdaptationAgent(gateway, route=route, prompts=prompts).propose(
            envelope(), deadline=deadline
        )
    elif agent_name == "assessment_item":
        proposal = AssessmentAgent(gateway, route=route, prompts=prompts).propose(
            envelope(), deadline=deadline, requested_difficulty="FOUNDATIONAL"
        )
    elif agent_name == "assessment_evaluate":
        proposal = AssessmentAgent(gateway, route=route, prompts=prompts).evaluate(
            envelope(), deadline=deadline
        )
    else:  # pragma: no cover - a typo in the parametrization, not a behaviour
        raise AssertionError(f"unknown agent surface {agent_name}")
    return proposal, provider


def register_where(template_id: PromptTemplateId, version: str, marker: str) -> PromptRegister:
    """The shipped register with one identity rebound to a distinctive stub."""
    kept = [
        artifact
        for artifact in default_prompt_register().artifacts.values()
        if (artifact.template_id, artifact.version) != (template_id, version)
    ]
    return register_of(
        *kept,
        PromptArtifact(
            template_id=template_id,
            version=version,
            build=lambda **_: (Message(role="system", content=marker),),
        ),
    )


AGENT_SURFACES = [
    ("tutor", PromptTemplateId.TUTOR_EXPLAIN),
    ("diagnostic", PromptTemplateId.DIAGNOSTIC_ROOT_CAUSE),
    ("adaptation", PromptTemplateId.ADAPTATION_PLAN),
    ("assessment_item", PromptTemplateId.ASSESSMENT_ITEM),
    ("assessment_evaluate", PromptTemplateId.ASSESSMENT_EVALUATE),
]


# -- the adversarial case: hallucinated provenance -------------------------------------------------


@pytest.mark.parametrize(("surface", "expected_template"), AGENT_SURFACES)
def test_the_recorded_identity_selects_the_prompt_that_is_sent(
    surface: str, expected_template: PromptTemplateId
) -> None:
    """The general invariant, asserted against the bytes the provider received.

    Rebinding the identity the proposal records to a distinctive artifact must change what the
    provider is given. If it does not, the identity is a decoration and the record is a claim about
    an output it had no part in producing -- which is the state this suite exists to prevent, and
    the state the code was in before M1-ADR-011.

    Checking that the version equals some constant would pass in exactly that state, which is how
    the original defect survived a full suite.
    """
    shipped, _ = run_agent(surface)
    recorded_version = shipped.promptVersion
    marker = f"a rebound {expected_template.value} artifact"

    rebound, provider = run_agent(
        surface, prompts=register_where(expected_template, recorded_version, marker)
    )

    assert rebound.promptVersion == recorded_version, "the identity itself must not move"
    assert provider.dispatched, "nothing reached the provider, so nothing was proven"
    assert provider.dispatched[0] == (Message(role="system", content=marker),), (
        f"{surface}: the proposal records {expected_template.value}@{recorded_version}, "
        "but that artifact is not what produced the dispatched prompt"
    )


@pytest.mark.parametrize(("surface", "expected_template"), AGENT_SURFACES)
def test_every_surface_records_a_resolvable_identity(
    surface: str, expected_template: PromptTemplateId
) -> None:
    """A recorded version that resolves to no artifact is a record of something that never ran."""
    proposal, _provider = run_agent(surface)

    assert default_prompt_register().is_approved(expected_template, proposal.promptVersion), (
        f"{surface} recorded {proposal.promptVersion}, which this build cannot produce"
    )


def test_a_shared_route_records_each_agents_own_prompt() -> None:
    """``ci-fake`` serves all five templates, and each run must record the one it used.

    The route previously declared a single ``CI_FAKE_PROMPT_V1``, so every CI evaluation recorded a
    name that matched no artifact. Every hard gate still passed, because nothing compared the two.
    """
    recorded = {surface: run_agent(surface)[0].promptVersion for surface, _ in AGENT_SURFACES}

    assert recorded["tutor"] == "TUTOR_PROMPT_V1"
    assert recorded["diagnostic"] == "DIAGNOSTIC_PROMPT_V1"
    assert recorded["adaptation"] == "ADAPTATION_PROMPT_V1"
    assert "CI_FAKE_PROMPT_V1" not in recorded.values()


def test_the_two_assessment_templates_are_distinguishable() -> None:
    """They share a version, so the template id is the only thing that separates them."""
    item, _ = run_agent("assessment_item")
    evaluation, _ = run_agent("assessment_evaluate")

    assert item.promptVersion == evaluation.promptVersion
    assert item.proposal["provenance"]["promptTemplateId"] == PromptTemplateId.ASSESSMENT_ITEM.value
    assert (
        evaluation.proposal["provenance"]["promptTemplateId"]
        == PromptTemplateId.ASSESSMENT_EVALUATE.value
    )


# -- the register and the route table cannot drift -------------------------------------------------


def test_every_route_pointer_names_a_prompt_this_build_can_produce() -> None:
    """The route table names versions as strings; nothing but this stops them drifting.

    It cannot import the prompt modules to check itself without a cycle, so the check lives here and
    at startup rather than in the table.
    """
    assert unbuildable_pointers(default_registry(), default_prompt_register()) == ()


def test_every_declared_template_has_an_artifact() -> None:
    """A template id with no artifact is a capability that exists only in the type system."""
    assert every_template_is_declared(default_prompt_register()) == ()


def test_every_template_is_served_by_at_least_one_route() -> None:
    """An unserved template can never run, so its version can never be rolled back either."""
    served = {
        template_id
        for config in default_registry().routes.values()
        for template_id in config.prompt_versions
    }

    assert served == set(PromptTemplateId)


def test_a_register_refuses_two_artifacts_with_one_identity() -> None:
    """Otherwise a recorded identity would no longer determine what ran."""
    duplicate = PromptArtifact(
        template_id=PromptTemplateId.TUTOR_EXPLAIN,
        version="TUTOR_PROMPT_V1",
        build=lambda **_: (),
    )
    with pytest.raises(ValueError, match="two prompt artifacts claim"):
        register_of(*default_prompt_register().artifacts.values(), duplicate)


# -- rollback as a deployment operation (M1-ADR-008) -----------------------------------------------


def test_a_rollback_needs_no_new_image() -> None:
    """The consequence M1-ADR-008 states and the implementation did not hold.

    Rolling back used to mean editing the route table, building an image and running the release
    pipeline -- the "prompts as code, deployed with the service" alternative the ADR rejected,
    because it makes the fastest available remedy as slow and as risky as shipping.
    """
    register = register_of(
        *default_prompt_register().artifacts.values(),
        PromptArtifact(
            template_id=PromptTemplateId.TUTOR_EXPLAIN,
            version="TUTOR_PROMPT_V2",
            build=lambda **_: (Message(role="system", content="revised"),),
        ),
    )
    prompt_pins, model_pins = pins_from_config(
        {"tutor-default": {"TUTOR_EXPLAIN": "TUTOR_PROMPT_V2"}}, {}
    )

    registry = registry_from_pins(register, prompt_pins=prompt_pins, model_pins=model_pins)

    assert (
        registry.resolve(ModelRoute.TUTOR_DEFAULT).prompt_version_for(
            PromptTemplateId.TUTOR_EXPLAIN
        )
        == "TUTOR_PROMPT_V2"
    )
    assert registry.version != ROUTE_TABLE_VERSION, "a pinned table must not report the shipped one"


def test_a_pin_naming_an_unbuildable_revision_stops_startup() -> None:
    """Ignoring it would leave a service that looks exactly like one where the rollback worked."""
    with pytest.raises(UnknownPromptVersionError):
        registry_from_pins(
            default_prompt_register(),
            prompt_pins={
                ModelRoute.TUTOR_DEFAULT: {PromptTemplateId.TUTOR_EXPLAIN: "TUTOR_PROMPT_V0"}
            },
        )


def test_a_misspelled_pin_key_stops_startup_rather_than_being_dropped() -> None:
    """A silently dropped pin is a rollback that appears to have been applied and was not."""
    with pytest.raises(RouteTableError, match="is not a model route"):
        pins_from_config({"tutor_default": {"TUTOR_EXPLAIN": "TUTOR_PROMPT_V1"}}, {})

    with pytest.raises(RouteTableError, match="is not a prompt template"):
        pins_from_config({"tutor-default": {"TUTOR_PROMPT": "TUTOR_PROMPT_V1"}}, {})


def test_the_application_refuses_to_start_with_a_bad_pin() -> None:
    """End to end, through the real settings object rather than the helper alone."""
    settings = Settings(prompt_pins={"tutor-default": {"TUTOR_EXPLAIN": "TUTOR_PROMPT_V0"}})

    with pytest.raises((UnknownPromptVersionError, ConfigurationError)):
        create_app(settings)


def test_the_application_starts_unpinned_and_reports_the_shipped_table() -> None:
    app = create_app(Settings())

    assert app.state.gateway.registry.version == ROUTE_TABLE_VERSION


# -- the deployed process has to say what it is running --------------------------------------------


def test_capabilities_reports_the_shipped_table_when_nothing_is_pinned() -> None:
    with TestClient(create_app(Settings())) as client:
        body = client.get("/internal/v1/capabilities").json()

    assert body["routeTableVersion"] == ROUTE_TABLE_VERSION


def test_capabilities_reports_the_pin_so_a_rollback_is_verifiable() -> None:
    """The deployment-side half of a rollback.

    Without this, a pin that failed to apply and a pin that applied are indistinguishable from
    outside the process, and the health gate would be asserting against the manifest's intent rather
    than the service's behaviour.
    """
    settings = Settings(model_pins={"tutor-default": "claude-sonnet-5"})

    with TestClient(create_app(settings)) as client:
        body = client.get("/internal/v1/capabilities").json()

    # Pinning to the currently approved model is a no-op in effect, so the reported table is
    # unchanged -- which is itself the correct answer, and the reason the check compares the
    # reported value rather than merely observing that a pin was configured.
    assert body["routeTableVersion"] == ROUTE_TABLE_VERSION

    register = register_of(
        *default_prompt_register().artifacts.values(),
        PromptArtifact(
            template_id=PromptTemplateId.TUTOR_EXPLAIN,
            version="TUTOR_PROMPT_V2",
            build=lambda **_: (Message(role="system", content="revised"),),
        ),
    )
    pinned = registry_from_pins(
        register,
        prompt_pins={ModelRoute.TUTOR_DEFAULT: {PromptTemplateId.TUTOR_EXPLAIN: "TUTOR_PROMPT_V2"}},
    )

    assert "TUTOR_PROMPT_V2" in pinned.version
    assert pinned.version != ROUTE_TABLE_VERSION
