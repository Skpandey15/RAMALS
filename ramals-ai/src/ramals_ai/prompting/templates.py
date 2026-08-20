"""Prompt artifacts, identified so a recorded identity is evidence rather than a claim.

The observability HLD does not log prompt text. It logs ``promptTemplateId`` and ``promptVersion``
*instead of* the prompt, alongside digests and safe metrics. That makes the identity a substitute
for the artifact: an investigator who reads ``TUTOR_EXPLAIN@TUTOR_PROMPT_V1`` in a log line is
entitled to reconstruct exactly what was sent. An identity that does not name the prompt that ran is
therefore not a cosmetic defect — it is the hallucinated provenance the Master Plan requires an
adversarial test against.

Two identifiers, because one cannot do the job:

* ``promptTemplateId`` says *which prompt*. The assessment agent has two — generating an item and
  evaluating a response are different instructions with different failure modes — so an agent
  identifier cannot distinguish them, and neither can a route shared by four agents.
* ``promptVersion`` says *which revision of it*. This is what M1-ADR-008 rolls back.

The structural decision is :class:`BuiltPrompt`. Messages and the identity that describes them are
produced together by :meth:`PromptRegister.build` and travel as one value, so no call site can send
one prompt and record another. Returning them separately would leave the correct pairing to
discipline at every call site, which is the arrangement that produced the defect this module fixes.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Any

from ramals_ai.gateway.providers.base import Message


class PromptTemplateId(StrEnum):
    """Which prompt. Stable across revisions; a new template is a new capability, not a new version.

    Named for what the prompt asks for rather than for the agent that holds it, because two of them
    live in the same agent and one agent's templates can be rolled back independently.
    """

    TUTOR_EXPLAIN = "TUTOR_EXPLAIN"
    DIAGNOSTIC_ROOT_CAUSE = "DIAGNOSTIC_ROOT_CAUSE"
    ASSESSMENT_ITEM = "ASSESSMENT_ITEM"
    ASSESSMENT_EVALUATE = "ASSESSMENT_EVALUATE"
    ADAPTATION_PLAN = "ADAPTATION_PLAN"


class UnknownPromptVersionError(RuntimeError):
    """A prompt version was named that this build cannot produce.

    Raised rather than defaulted. Falling back to the current version would let a rollback silently
    not happen, which is indistinguishable from a rollback that worked until someone reads the
    outputs — the exact failure mode M1-ADR-008 exists to prevent.
    """

    def __init__(self, template_id: PromptTemplateId, version: str, approved: tuple[str, ...]):
        super().__init__(
            f"no prompt artifact for {template_id}@{version}; "
            f"this build provides {list(approved) or 'nothing for that template'}"
        )
        self.template_id = template_id
        self.version = version
        self.approved = approved


@dataclass(frozen=True)
class BuiltPrompt:
    """Messages, and the identity of the artifact that produced them.

    One value, deliberately. The identity is not a label a caller attaches afterwards; it is what
    the register stamped when it built these exact messages.
    """

    template_id: PromptTemplateId
    version: str
    messages: tuple[Message, ...]

    @property
    def identity(self) -> str:
        """``TEMPLATE@VERSION``, for a log line or a failure message."""
        return f"{self.template_id.value}@{self.version}"


@dataclass(frozen=True)
class PromptArtifact:
    """One revision of one prompt: an identity and the code that produces it.

    The builder is held here rather than looked up beside the register, so a version cannot be
    declared approved without something that can actually build it. A register of version *strings*
    would let a future release add ``TUTOR_PROMPT_V2`` to a table, roll back to it, and send V1.
    """

    template_id: PromptTemplateId
    version: str
    build: Callable[..., tuple[Message, ...]]


@dataclass(frozen=True)
class PromptRegister:
    """Every prompt revision this build can produce.

    Shipped with the image, which is the right coupling: *adding* an approved prompt is a release,
    while *returning to* one already in the image is a pointer change requiring no deployment
    (M1-ADR-008). A rollback target that is not in the image is not a rollback target — it is a
    prompt nobody reviewed.
    """

    artifacts: Mapping[tuple[PromptTemplateId, str], PromptArtifact]

    def approved_versions(self, template_id: PromptTemplateId) -> tuple[str, ...]:
        """Every version of this template the build can produce, sorted for a stable message."""
        return tuple(sorted(version for (tid, version) in self.artifacts if tid is template_id))

    def is_approved(self, template_id: PromptTemplateId, version: str) -> bool:
        return (template_id, version) in self.artifacts

    def resolve(self, template_id: PromptTemplateId, version: str) -> PromptArtifact:
        try:
            return self.artifacts[(template_id, version)]
        except KeyError:
            raise UnknownPromptVersionError(
                template_id, version, self.approved_versions(template_id)
            ) from None

    def build(
        self,
        template_id: PromptTemplateId,
        version: str,
        /,
        *args: Any,
        **kwargs: Any,
    ) -> BuiltPrompt:
        """Builds the named revision and stamps the identity onto the result it produced."""
        artifact = self.resolve(template_id, version)
        return BuiltPrompt(
            template_id=template_id,
            version=version,
            messages=artifact.build(*args, **kwargs),
        )


def register_of(*artifacts: PromptArtifact) -> PromptRegister:
    """Builds a register, refusing a duplicate identity.

    Two artifacts claiming one identity means a recorded identity no longer determines what ran,
    which is the property the whole module exists to hold.
    """
    indexed: dict[tuple[PromptTemplateId, str], PromptArtifact] = {}
    for artifact in artifacts:
        key = (artifact.template_id, artifact.version)
        if key in indexed:
            raise ValueError(
                f"two prompt artifacts claim {artifact.template_id}@{artifact.version}"
            )
        indexed[key] = artifact
    return PromptRegister(artifacts=indexed)
