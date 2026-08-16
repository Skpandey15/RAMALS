# M1-ADR-001: Interaction classes, deadlines and timeout semantics

- **Status:** Accepted
- **Date:** 2026-08-16
- **Relates to:** MVP-1 Canonical Package v1.3 Doc 01 §5/§10, Doc 04 §7, Doc 02 §4
- **Required before:** M1-T02, M1-T08

## Context

Every Spring-to-AI call needs a classification before it is written, or MVP-1 becomes a synchronous
distributed monolith by accident: a request that waits on a model, holding a thread and possibly a
database connection, is indistinguishable from a slow query until the provider has a bad day.

Doc 01 §5 fixes the classes and their ceilings; Doc 04 §2 fixes tighter per-route ceilings. What was
not written down is what a deadline *means* when it is exceeded, and who is responsible for it.

## Decision

### Classes and ceilings

Ratified from Doc 01 §5. These are engineering qualification guardrails, not customer SLAs, and are
versioned with the package.

| Class | In MVP-1 | Ceiling |
| --- | --- | --- |
| `FAST` | yes | p95 ≤ 1.0 s; hard deadline ≤ 2.0 s |
| `INTERACTIVE_AI` | yes | TTFT p95 ≤ 2.5 s when streaming; complete p95 ≤ 8.0 s; hard deadline ≤ 12.0 s |
| `ASSESSMENT_PROPOSAL` | yes | complete p95 ≤ 10.0 s; hard deadline ≤ 15.0 s |
| `LIMITED_DURABLE` | yes, approval only | initial persistence p95 ≤ 1.0 s; HTTP returns after persistence |
| `GENERAL_DURABLE` | **no** | MVP-2 adoption gate |

**Precedence is stricter-wins.** Where a Doc 04 route ceiling is tighter than the class ceiling, the
route governs. A route may never invoke the broader class limit to exceed its own budget. Example:
`diagnostic-default` at 6.0 s governs over `INTERACTIVE_AI` at 8.0 s.

### Timeout semantics

1. **The caller owns the deadline.** Spring computes an absolute deadline before the call and sends
   the remaining budget. `ramals-ai` must not exceed it, and must not start work it cannot finish
   within it.
2. **Deadlines are absolute, not per-hop.** A retry consumes the original budget; it does not reset
   it. Otherwise a bounded retry policy becomes an unbounded latency policy.
3. **Exceeding a deadline fails closed.** No partial proposal, no fallback to a lower-trust answer,
   no fabricated learner state. The learner sees an AI-unavailable outcome with a support code.
4. **No authoritative database transaction is open across an AI call**, at any class. Spring commits
   pre-state before calling and opens a new transaction after validation if a write is permitted.
5. **Cancellation propagates.** A cancelled client request cancels the downstream model call where
   the provider supports it, and the graph stops at its next node boundary regardless.
6. **`LIMITED_DURABLE` returns after persistence.** The HTTP response completes once the
   `APPROVAL_REQUIRED` state is durable. Waiting for a human inside a request is the failure mode
   this class exists to prevent.

## Alternatives considered

**One deadline for all AI calls.** Simpler, but either too tight for assessment generation or too
loose for a tutor hint, and it removes the vocabulary needed to reason about which calls may block a
learner interaction.

**Per-hop timeouts instead of an absolute deadline.** Common and appealing, but the hops compose:
three hops with a 5 s timeout each is a 15 s worst case that no one wrote down.

## Consequences

- The Spring AI port takes a deadline, not a timeout, and every downstream budget derives from it.
- `ramals.ai.latency` is measured per class and per route, and the stricter applicable ceiling is
  the gate (M1-T14).
- Adding a class, or relaxing a ceiling, is a package change and a new ADR — not a configuration
  tweak.
