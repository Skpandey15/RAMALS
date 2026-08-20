"""The prompt artifacts this build ships.

Assembled from what each agent declares beside its own prompt, rather than from a central list of
version strings. The difference matters: a central list can name a version no code can build, and a
rollback to such a version would silently keep sending the current prompt.

Kept in its own module so the agent prompt modules depend only on
:mod:`ramals_ai.prompting.templates` and nothing depends back on them — the register imports the
agents, the agents do not import the register.
"""

from __future__ import annotations

from ramals_ai.adaptation import prompt as adaptation_prompt
from ramals_ai.assessment import prompt as assessment_prompt
from ramals_ai.diagnostic import prompt as diagnostic_prompt
from ramals_ai.prompting.templates import PromptRegister, PromptTemplateId, register_of
from ramals_ai.tutor import prompt as tutor_prompt

_DECLARING_MODULES = (
    tutor_prompt,
    diagnostic_prompt,
    assessment_prompt,
    adaptation_prompt,
)


def default_prompt_register() -> PromptRegister:
    """Every prompt revision in this image.

    A fresh register each call, following the route table's rule: nothing hands out a shared mutable
    default that a caller could widen for the whole process.
    """
    return register_of(
        *(artifact for module in _DECLARING_MODULES for artifact in module.PROMPT_ARTIFACTS)
    )


def every_template_is_declared(register: PromptRegister) -> tuple[PromptTemplateId, ...]:
    """Templates the enum names but no module builds.

    A template id with no artifact is a capability that exists in the type system and nowhere else.
    Returned rather than raised so the caller decides whether that is a startup failure or a test
    assertion — it is both, in different places.
    """
    return tuple(
        template_id
        for template_id in PromptTemplateId
        if not register.approved_versions(template_id)
    )
