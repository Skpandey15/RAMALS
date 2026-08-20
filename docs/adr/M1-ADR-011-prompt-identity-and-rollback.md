# M1-ADR-011: Prompt identity is `promptTemplateId` + `promptVersion`, and a rollback moves it

- **Status:** Proposed
- **Date:** 2026-08-20
- **Task:** M1-T17 slice 2
- **Amends:** [M1-ADR-008](M1-ADR-008-model-routing-fallback-and-rollback.md)
- **Sources:** RAMALS Business Logging / Exception / Observability HLD-LLD v1.0 §9–§10; RAMALS
  Updated Implementation Master Plan v2.0 §14

## Context

M1-ADR-008 decided that prompts are versioned artifacts the gateway points at, rejected "prompts as
code, deployed with the service" because it makes prompt rollback as slow and as risky as shipping,
and recorded as a consequence that *rollback is a pointer change plus a smoke evaluation, available
without a service deployment*.

The implementation was the rejected alternative, and had three defects that reinforced each other.

**A rollback could not be performed.** `RouteRegistry.rolled_back()` existed and was called from
tests only. The gateway always built the hardcoded default table, so withdrawing a prompt meant
editing source, building an image and running the release pipeline.

**A rollback would have falsified provenance.** `prompt_version` was a label. Nothing resolved it to
a prompt: `build_messages(context, requested_capability)` took no version, so the messages were a
pure function of the request. Rolling back moved the version recorded on every subsequent proposal
and left the dispatched bytes identical. `rolled_back()` also accepted any string, including
`TUTOR_PROMPT_V0`, a revision that has never existed.

**A single version per route could not name the prompt that ran.** `ci-fake` serves all four agents
and declared one `CI_FAKE_PROMPT_V1`, so every CI evaluation recorded an identity matching no
artifact. The assessment agent has two prompts — generating an item and evaluating a response —
sharing one version, so `ASSESSMENT` and `ASSESSMENT_EVALUATE` were indistinguishable in the record
although `evaluation/baselines.json` treats them as separate agents.

A test named `test_the_reported_prompt_version_follows_the_route_not_the_agent` asserted the pointer
semantics and passed throughout, reasoning that otherwise "rolling a prompt back would change what
the tutor sends and not what the proposal claims". The truth was the exact inverse.

## Why this is a correctness problem and not a naming one

The Observability HLD does not log prompt text. §9 lists `promptTemplateId` and `promptVersion` as
context fields, and §10 makes the relationship explicit: against "unrestricted prompt/LLM output"
the preferred alternative is "promptVersion + digests + safe metrics". The identity is logged
**instead of** the artifact. It is therefore the only evidence an investigator has about what
produced an output, and one that does not name the prompt that ran is a record asserting something
untrue in the field most likely to be trusted.

The Master Plan §14 lists **hallucinated provenance** among the AI-adversarial cases a release must
be tested against. This is that case, produced by the platform itself rather than by a model.

## Decision

### A prompt is identified by template and revision

`promptTemplateId` says *which prompt*; `promptVersion` says *which revision*. Two identifiers,
because neither shape that occurs in practice can be described by one: a route shared by four agents,
and one agent holding two prompts.

Templates are named for what the prompt asks for — `TUTOR_EXPLAIN`, `ASSESSMENT_ITEM`,
`ASSESSMENT_EVALUATE` — rather than for the agent that holds them, because the agent is not what
distinguishes them.

### The identity resolves to the code that produces the prompt

A `PromptArtifact` is an identity **and its builder**, and a `PromptRegister` maps identities to
artifacts. A version that no artifact can build does not exist: `PromptRegister.resolve` raises.

This is what makes the identity evidence rather than decoration. A register of version *strings*
would let a later release add `TUTOR_PROMPT_V2` to a table, roll back to it, and keep sending V1.

### Messages and identity are produced together

`PromptRegister.build` returns a `BuiltPrompt` — template, version and messages as one value — and
that value travels through the graph and into the gateway. No call site can dispatch one prompt and
record another, because no call site ever holds them separately.

Agents name the **template**; the route's pointer supplies the version. An agent never gets to say
which revision it wants, so it cannot ask for one and record another.

### A route points at one revision per template it serves

`ci-fake` carries five pointers rather than one meaningless string. `CI_FAKE_PROMPT_V1` is removed;
it named nothing.

### Rollback is applied from configuration and validated at startup

`RAMALS_AI_PROMPT_PINS` and `RAMALS_AI_MODEL_PINS` repoint a route at a revision **this image
already ships**. Adding an approved prompt is a release; returning to one is a pointer change. Both
pins are validated at startup against what the build can produce, and a bad pin stops the process:
a service that starts while silently ignoring a rollback is indistinguishable from one that applied
it, and the difference only surfaces in the outputs somebody was trying to stop producing.

A model pin is checked against the route's `approved_models` for the same reason a prompt pin is
checked against the register — an unchecked pin is a way to put unreviewed inference in front of
learners with an environment variable.

### The running service reports what it is serving

`RouteRegistry.version` is the shipped table version when nothing is pinned, and names the pins when
something is. It is recorded with every call and reported by `/internal/v1/capabilities` as
`routeTableVersion` — an optional, additive contract field.

`deploy/health-gates.sh` asserts the plane reports one, and when `AI_EXPECTED_ROUTE_TABLE` is set,
that it matches. This is M1-ADR-008's "a rollback is a deployment" made checkable: the manifest
records an intention, and this records a fact.

## Alternatives considered

**Keep the version on the agent; routes carry model and budget only.** Simpler, and arguably truer
to where a prompt belongs. Rejected because it contradicts M1-ADR-008's decision that rollback moves
a *route* pointer, and because it does not solve the assessment agent's two templates — an
agent-scoped identifier is exactly what cannot distinguish them.

**Leave the semantics and only fix reachability.** The smallest change: make rollback reachable and
refuse versions naming no prompt. Rejected because it ships the deployment lever while leaving the
lever able to falsify records, which is worse than having no lever.

**Make `promptVersion` globally unique and drop the template id.** Encoding the template into the
version string (`ASSESSMENT_ITEM_V1`) needs no new field. Rejected: it makes the two identifiers one
string that must be parsed to be useful, and a version is then no longer comparable across a
template's revisions without string surgery.

**Validate prompt pins by review rather than at startup.** Rejected for the reason the route table
is validated at import time rather than by review: a check that protects one path is weaker than one
that makes the bad configuration unrepresentable.

## Consequences

- A recorded prompt identity can be resolved back to the exact prompt that was sent. This is what
  makes an evaluation regression diagnosable and a disputed output reconstructible.
- The route table and the prompt modules cannot drift: `unbuildable_pointers` is asserted in a test
  and at startup. The table names versions as literal strings because it cannot import the prompt
  modules without an import cycle, so the check is not optional.
- MVP-1 ships exactly one revision per template, so **there is currently no rollback target**. The
  mechanism is proven by tests that add a second revision. This is the honest state of a first
  release, and it means the first prompt revision automatically creates a rollback target rather
  than requiring somebody to remember to build the mechanism then.
- `evaluation/baselines.json` records `promptVersion` against `modelRoute: ci-fake`; those pairings
  now correspond to what a run actually reports, which they previously did not.
- The durable `core.ai_execution` record still carries `promptVersion` without a template id, so
  an assessment item and an assessment evaluation remain indistinguishable **in the database**.
  Closing that is an additive column and is tracked as the first item of M1-T17 slice 3, where the
  expand/contract migration work already lives.

## Verification

- Rebinding the identity a proposal records to a distinctive artifact changes the bytes the provider
  receives — asserted for all five agent surfaces. Asserting the version equals a constant would
  pass in the broken state, which is how the original defect survived a full suite.
- A rollback target this build cannot produce is refused.
- A rollback moves both the recorded identity and the dispatched messages.
- A rollback leaves already-recorded proposal metadata byte-identical.
- Model and prompt roll back independently, and one template's rollback does not move another's.
- A misspelled pin key stops startup rather than being dropped.
- Every route pointer names a prompt this build can produce; every declared template has an
  artifact; every template is served by at least one route.
- The deployed plane reports its effective route table, and the health gate fails when a rollback
  did not take effect — verified by perturbation against a stubbed HTTP surface.
