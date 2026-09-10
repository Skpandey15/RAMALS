"""The MVP-2 DiagnosticProbeProposal, mirroring ``diagnostic-probe-proposal.v1.schema.json``.

Hand-written rather than generated, following the same rule as ``diagnostic_assessment.contracts``:
the MVP-2 contracts live in ``contracts/mvp2/`` as JSON Schema, outside the OpenAPI envelope that
produces ``contracts.generated``. ``test_golden_round_trip`` validates a produced proposal against
that schema file, so the two cannot drift without a test failing.

Distinct from :class:`~ramals_ai.diagnostic_assessment.contracts.DiagnosticAssessmentProposal`:
that one is a skill-by-skill *classification* and carries a ``confidence``. This one asserts nothing
about the learner -- it names an unresolved evidentiary ambiguity and a bounded next observation.
There is no ``confidence``, ``probability``, ``score``, ``rank``, or ``diagnosis`` field, and
``extra="forbid"`` makes adding one unrepresentable rather than merely discouraged (M2-ADR-032 3).
"""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

MAX_EVIDENCE_REFS = 64
MAX_RATIONALE_CHARS = 1000

_UUID_PATTERN = r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"

Identifier = Annotated[str, Field(min_length=1, max_length=64)]
Uuid = Annotated[str, Field(pattern=_UUID_PATTERN)]


class TargetNodeKind(StrEnum):
    """The ontology entity kind a misconception is attached to, per M2-ADR-026's exclusive arc."""

    LEARNING_OBJECTIVE = "LEARNING_OBJECTIVE"
    CONCEPT = "CONCEPT"
    SUB_CONCEPT = "SUB_CONCEPT"


class ProbeIntent(StrEnum):
    """A bounded, closed set of advisory intents (M2-ADR-032 8).

    Neither value asserts a diagnosis, a probability, or that a probe is eligible to run. Probe
    eligibility and execution stay with ``DIAGNOSTIC_SELECTION_V1-V5``, untouched.
    """

    COLLECT_ADDITIONAL_MISCONCEPTION_EVIDENCE = "COLLECT_ADDITIONAL_MISCONCEPTION_EVIDENCE"
    DISCRIMINATE_BETWEEN_EVIDENCE_STATES = "DISCRIMINATE_BETWEEN_EVIDENCE_STATES"


class TargetNode(BaseModel):
    """The single ontology entity the target misconception is attached to.

    Must exactly match the misconception's real target (kind and id); it is not chosen freely. Java
    re-derives the true arc target and rejects a mismatch (M2-ADR-032 12).
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    kind: TargetNodeKind
    id: Uuid


class DiagnosticProbeProposal(BaseModel):
    """An advisory, non-authoritative recommendation for one next diagnostic evidence-acquisition
    probe.

    Nothing here is authoritative. It carries no diagnosis, no classification, no ranking, no
    confidence value, and no instruction to run anything. Java's deterministic, versioned,
    fail-closed ``DiagnosticProbeProposalGate`` independently decides whether it has any effect.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    contractVersion: Literal["1.0"] = "1.0"  # noqa: N815
    proposalType: Literal["DIAGNOSTIC_PROBE_CANDIDATE"] = "DIAGNOSTIC_PROBE_CANDIDATE"  # noqa: N815
    proposalId: Identifier  # noqa: N815
    requestId: Identifier  # noqa: N815
    agentRunId: Identifier  # noqa: N815
    interactionId: Identifier  # noqa: N815
    """Echoed for cross-check only. Java binds the authoritative interaction from its own context
    and rejects a mismatch; a value here can never select or override the interaction."""

    domain: Annotated[str, Field(min_length=1, max_length=64)]
    """The domain the recommendation concerns. Cross-checked against the interaction's authorized
    domain by Java; never widens scope."""

    targetMisconceptionId: Uuid  # noqa: N815
    """An existing, already-authored, PUBLISHED misconception id. Must be a member of the id set
    exposed to this interaction (M_allowed); independently re-validated by Java (M2-ADR-032 12)."""

    targetNode: TargetNode  # noqa: N815
    probeIntent: ProbeIntent  # noqa: N815

    candidateProbeRef: Uuid | None = None  # noqa: N815
    """Optional. A specific authored probe object. Naming it is never authorization to run it
    (M2-ADR-032 13); in step 3 the authorized set is empty, so any concrete value is rejected."""

    evidenceRefs: Annotated[  # noqa: N815
        list[Identifier], Field(min_length=1, max_length=MAX_EVIDENCE_REFS)
    ]
    """E_proposed: governed evidence/provenance identifiers the recommendation cites. Java enforces
    ``E_proposed subset of E_allowed`` independently of the prompt (M2-ADR-032 11)."""

    rationale: Annotated[str, Field(min_length=1, max_length=MAX_RATIONALE_CHARS)]
    """Bounded natural language. Must not contain probability/percentage language, comparative
    'more/less likely' between misconceptions, or resolution terminology -- Java rejects it
    lexically and deterministically (M2-ADR-032 9)."""

    @field_validator("evidenceRefs")
    @classmethod
    def reject_duplicate_references(cls, value: list[str]) -> list[str]:
        """``uniqueItems`` in the schema. A duplicate is usually a model padding a list."""
        if len(set(value)) != len(value):
            raise ValueError("EVIDENCE_REFS_NOT_UNIQUE")
        return value

    def to_contract(self) -> dict[str, object]:
        """The wire form, exactly as ``diagnostic-probe-proposal.v1.schema.json`` describes it.

        ``exclude_none`` so an absent ``candidateProbeRef`` stays absent rather than becoming an
        explicit null, matching the frozen fixture and the Java parser's expectation.
        """
        return self.model_dump(mode="json", exclude_none=True)
