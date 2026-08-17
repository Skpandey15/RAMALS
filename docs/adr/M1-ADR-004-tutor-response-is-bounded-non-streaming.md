# M1-ADR-004: The Tutor returns a bounded, non-streaming response

- **Status:** Accepted
- **Date:** 2026-08-17
- **Relates to:** MVP-1 Canonical Package v1.3 Doc 01 §4, Doc 02 §3, Doc 07 §3, M1-ADR-001
- **Required before:** M1-T08

## Context

Doc 01 gives `INTERACTIVE_AI` a streaming target — "stream TTFT p95 ≤ 2.5 s **when used**" — alongside
a complete-response target of p95 ≤ 8.0 s and a hard deadline of 12.0 s. The phrasing is deliberate:
streaming is permitted, not required, and whether Tutor V1 uses it is left open.

The pull toward streaming is real. Eight seconds is a long time to watch a spinner, and first-token
latency is the single most effective lever on *perceived* responsiveness in a chat-shaped interface.
Every product instinct says stream.

The pull against it only became concrete once M1-T07 existed. The Tutor validates its output for
**unsupported learner-state claims** — sentences like "you struggled with this last week" that
fabricate a fact about a person, read as attentive personalization, and are indistinguishable to the
learner from something the platform actually knows (Doc 07 §3). It also rejects announced mastery
verdicts, because progression is decided by a deterministic engine from recorded evidence.

Those checks operate on a complete response. That is not an implementation shortcut; it is inherent.
You cannot tell whether a sentence makes an unsupported claim until the sentence exists, and by then
a streaming interface has already shown it.

## Decision

**Tutor V1 returns a single, complete, validated response. It does not stream.**

- The Spring AI port makes one request and receives one proposal. There is no token channel, no
  server-sent events, and no partial rendering.
- Doc 01's `INTERACTIVE_AI` complete-response target (p95 ≤ 8.0 s) and hard deadline (12.0 s) are the
  applicable budgets. The TTFT target does not apply, because streaming is not used.
- The learner-facing wait is addressed in the interface — an explicit pending state with the
  interaction's support code visible, and a working cancel — not by showing unvalidated text.

### Why the validation argument is decisive

Streaming and post-hoc validation are mutually exclusive in the way that matters. Three options
exist and none of them work:

**Validate incrementally.** Impossible for the checks that matter. "Last time you got this wrong" is
only detectable once complete; a partial "Last time you" is not yet a claim.

**Stream, then retract.** The learner has already read it. A correction that arrives after a
fabricated statement about the learner's own history is worse than the statement alone — it tells
them the system says things it cannot support, which is precisely the trust the deterministic core
exists to protect.

**Stream without validating.** Abandons a Doc 07 primary measure to save perceived latency.

There is also a structural reason. The bounded repair loop in Doc 02 §3 routes invalid output back
through `bounded_repair` for another attempt. A repaired response replaces the previous one — which
is coherent only if the previous one was never shown.

### What this does not decide

- **Other agents.** Diagnostic, Assessment and Adaptation produce proposals consumed by Spring, not
  prose read live by a learner; streaming is not meaningful for them and this ADR does not discuss it.
- **Tutor V2 and beyond.** If a future tutor separates a freely-streamable *explanation of a concept*
  from *claims about the learner*, streaming the former becomes arguable. That is a different design
  with a different validation story, and it needs its own decision.
- **Perceived latency generally.** This says the fix is not "show unvalidated tokens", not that
  eight seconds is fine.

## Alternatives considered

**Stream with a validation gate on a trailing window.** Buffer the last N tokens, validate the
completed sentence, and release it. Rejected: the claims worth catching span sentences ("You
mentioned partitions last week. Let's revisit."), the buffer that would catch them is large enough to
destroy the TTFT benefit, and the result is a system that is neither responsive nor safe.

**Stream only the explanation, hold back the checks for understanding.** Attractive, and closer to
the Tutor V2 direction above. Rejected for V1 because the explanation is exactly where a fabricated
learner-state claim would appear — it is the personalized part.

**Stream in local and dev, complete in shared environments.** Rejected outright. Two response modes
means two validation stories, and the one exercised least is the one that runs where learners are.
This is the same reasoning that made M1-T04 reject invalid correlation identically in every profile.

**Raise the complete-response target instead of streaming.** Not this ADR's to give. Doc 01 owns the
class budgets, and 8.0 s p95 is the number to design against, not around.

## Consequences

- The Spring AI port is a request/response call. Simpler to bound, cancel and circuit-break than a
  long-lived stream — the deadline model in M1-ADR-001 applies unchanged.
- The UI must make an eight-second wait tolerable without content: a pending state, the support code
  on screen from the start, and a cancel that actually abandons the request.
- Tutor latency is felt in full. If p95 approaches the 8.0 s target in practice, the remedies are
  prompt and route work, or a Tutor V2 that separates concept from learner claims — not streaming
  the current design.
- Every response a learner sees has passed schema and semantic validation. That is the property this
  buys, and it is the reason the trade is worth making.

## Verification

- No streaming transport exists on the tutor path: the AI port exposes one request/response method,
  and no SSE or chunked endpoint is registered.
- A proposal that fails validation renders no learner-visible content (already asserted in M1-T07:
  an unusable output becomes an empty proposal carrying reason codes, never raw model text).
- The UI shows a pending state and the support code before any content arrives, and cancellation
  abandons the request rather than hiding it.
- Doc 01's complete-response budget is the one enforced on the tutor path; no TTFT assertion exists,
  because there is no first token to measure.
