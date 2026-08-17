# M1-ADR-008: Model routing, fallback and prompt/model rollback

- **Status:** Accepted
- **Date:** 2026-08-17
- **Relates to:** MVP-1 Canonical Package v1.3 Doc 04, Doc 01 §4, M1-ADR-001, M1-ADR-002
- **Required before:** M1-T05

## Context

Every AI call in MVP-1 goes through one gateway. That gateway has to answer three questions that
sound routine and are not:

1. **Which model configuration served this request?** Not "which provider" — which *versioned
   configuration*, including the prompt. A proposal recorded without that is unreproducible: when a
   learner disputes an outcome six weeks later, or when an evaluation run regresses, there is no way
   to establish what actually produced the output.
2. **What may the gateway do when a call fails?** A retry is cheap and usually right. A fallback to
   a different model is neither: it silently changes what produced the answer, and can change cost
   by an order of magnitude. Left unstated, "handle errors gracefully" becomes "escalate to the
   expensive model on every hiccup", discovered at the end of a billing period.
3. **How is a bad model or prompt withdrawn?** Prompts are deployed artifacts that change behaviour
   as much as code does, and a prompt regression looks like a quality complaint, not an incident.

Doc 04 fixes the numbers — per-route cost ceilings, token ceilings, p95 targets and a fallback
table. It does not say how those numbers are enforced, what happens at the boundary between a
route's budget and an interaction class's budget, or what rollback is permitted to touch. Those are
the decisions here.

The MVP-0 precedent that matters: seven deterministic engines were frozen by version, with a test
that hashes each engine's behaviour over fixed vectors so a silent change fails the build. Model
routes are the non-deterministic analogue of the same problem, and get the same treatment as far as
non-determinism allows — the *configuration* is frozen and asserted even though the *output* cannot
be.

## Decision

### Routes are configuration, not code paths

The five routes in Doc 04 §2 — `tutor-default`, `diagnostic-default`, `assessment-default`,
`adaptation-default`, `ci-fake` — are data: a versioned record of model identifier, provider
parameters, prompt version, token ceilings, cost ceiling and completion target. Adding a route is a
configuration change with a review, not a new branch in the gateway.

**No agent, node or service imports a provider SDK.** The only module that may is the LiteLLM
adapter behind `LLMGateway`. This is enforced by a test that scans imports, not by convention —
the same reasoning as the engine freeze scan: a rule nothing checks is a preference.

### Budgets are enforced before the call, not observed after it

Doc 04 distinguishes soft targets from hard ceilings. Only hard ceilings are enforcement:

- **Token ceilings** are checked against the assembled request *before* dispatch. Input over the
  route's ceiling fails; it is never silently truncated, because truncating a minimized context
  removes the part the platform decided the learner may see and leaves no trace that it happened.
- **Cost ceilings** are enforced per request, from the route's own configuration. A request whose
  projected cost exceeds the ceiling does not run.
- **Deadlines** come from the caller as an absolute instant (M1-ADR-001), not as a timeout. The
  gateway derives every downstream budget from the remaining time.

**Where a route budget and an interaction-class budget disagree, the stricter one governs**
(Doc 04 §7). `diagnostic-default` at p95 ≤ 6.0 s governs over `INTERACTIVE_AI` at 8.0 s. This is
stated in M1-ADR-001 and repeated here because it is the rule most likely to be got wrong in
implementation, where the route config is the nearer object.

### Fallback is permitted only where it cannot change the answer's provenance silently

Following Doc 04 §4, with the ambiguities resolved:

| Failure | Response |
|---|---|
| 429 or transient 5xx | Bounded retry with backoff, within the caller's remaining deadline. Fallback to an approved, semantically equivalent route only if configured for that route. |
| Timeout | Cancel. Fallback **only** if the caller's deadline still permits a complete second attempt — never a partial one. |
| Invalid structured output | Bounded repair, on the **same** governed route. Repair is not an excuse to change models. |
| Budget exceeded | Stop. **No escalation to a more expensive route**, ever, under any failure. |
| Auth or configuration error | Fail immediately, emit a metric. These are never retried: retrying a misconfiguration turns one alert into a flood and delays the fix. |

Two constraints bind all of it:

- **Retries and fallbacks consume the caller's deadline, not their own.** A "bounded retry" that
  outlives the deadline is a deadline violation wearing a different name.
- **A fallback is recorded.** The effective `modelRoute` persisted with the proposal is the route
  that *actually served it*, not the one requested. A proposal that silently records the intended
  route is worse than one recording nothing, because it reads as trustworthy.

### Rollback changes pointers, never history

Model configuration and `promptVersion` are immutable and versioned. The previous approved
configuration remains available and deployable.

- Rollback changes the route's version pointer. It **never** rewrites the `modelRoute` or
  `promptVersion` recorded on proposals already produced. Those record what happened; editing them
  to match the current configuration would destroy the only evidence of why an output looked the way
  it did.
- Model and prompt roll back **independently**. A prompt regression should not force a model
  rollback, and coupling them makes the cheap fix as risky as the expensive one.
- A contract and safety smoke evaluation runs after any rollback, before the route is considered
  live. A rollback is a deployment.

### `ci-fake` is a first-class route, not test scaffolding

`ci-fake` is deterministic, costs nothing, and requires no provider credential. CI runs the full
agent path on it. It is refused in the `dev` environment when AI is enabled — already enforced in
`Settings._reject_fake_route_outside_test` — because deterministic canned output that reaches a
shared environment is indistinguishable from a working model until someone reads the answers.

## Alternatives considered

**Let each agent choose its model.** Rejected. It puts cost and safety decisions in the component
least able to reason about them and makes per-route budgets unenforceable — there would be no route.
It also guarantees provider SDK imports spread through the codebase, which is the thing hardest to
undo later.

**Route directly to providers; skip LiteLLM.** Fewer moving parts, and one less dependency to audit.
Rejected because normalizing error taxonomy, token accounting and parameter naming across providers
is exactly the work LiteLLM already does, and doing it by hand means discovering each provider's
quirks in production. The adapter boundary means this can be revisited without touching any agent.

**Automatic escalation to a stronger model on failure.** Superficially attractive for quality, and
the reason many systems have unexplainable bills. Rejected outright: it converts a transient error
into unbounded cost, and it changes what produced an answer at exactly the moment nobody is
watching. Escalation, if ever wanted, is a product decision with its own budget — not an error
handler.

**Prompts as code, deployed with the service.** Simple, and versioned for free by git. Rejected
because it couples prompt rollback to a service deployment, which makes the fastest available
remedy for a quality regression as slow and as risky as shipping. Prompts are versioned artifacts
the gateway points at.

**Truncate oversized input rather than failing.** Rejected. The input is a *minimized context* that
Spring built after authorizing the learner; silently dropping part of it produces a confidently
wrong answer from a subset nobody chose, and leaves no signal that it happened. Failing is loud,
correct, and points at the real problem, which is context assembly.

## Consequences

- `LLMGateway` is the only component that knows a provider exists. An import-scanning test enforces
  it, so the boundary cannot erode quietly.
- Every proposal carries the effective `modelRoute` and `promptVersion`. This is what makes an
  evaluation regression diagnosable and a disputed outcome reconstructible.
- Cost is bounded per request by construction rather than by monitoring. Monitoring tells you what
  you spent; a ceiling decides it.
- Rollback is a pointer change plus a smoke evaluation, available without a service deployment.
- CI never needs a paid provider, so a fork or a fresh checkout runs the full agent path.
- Route configuration becomes a reviewed artifact with its own change history. That is deliberate
  overhead: it is the record of what the system was allowed to spend and say, at any point in time.

## Verification

These are the assertions M1-T05 must carry, stated here so the ADR is falsifiable rather than
aspirational:

- No module outside the LiteLLM adapter imports a provider SDK — enforced by an import scan over
  sources, not by review.
- A request exceeding a route's input-token ceiling is refused before dispatch, and nothing is sent.
- A request exceeding a route's hard cost ceiling is refused, and no fallback route runs.
- Budget exhaustion never selects a more expensive route; asserted as a negative test.
- Timeout, 429 and 5xx each normalize to the documented taxonomy, distinguishably.
- Retries and fallbacks respect the caller's absolute deadline, verified against a deadline that
  expires mid-retry.
- The persisted `modelRoute` reflects the route that served the request after a fallback, not the
  route requested.
- A rollback changes the active pointer and leaves previously recorded proposal metadata byte-identical.
- `ci-fake` produces identical output for identical input across runs.
