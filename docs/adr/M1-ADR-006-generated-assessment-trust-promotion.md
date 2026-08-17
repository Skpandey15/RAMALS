# M1-ADR-006: Generated assessment content is UNVERIFIED until it passes a staged trust pipeline

- **Status:** Accepted
- **Date:** 2026-08-17
- **Relates to:** MVP-1 Canonical Package v1.3 Doc 03 §4, Doc 07 §2–§3, M1-ADR-010
- **Required before:** M1-T10

## Context

M1-T10 is where AI-generated content first enters the platform: candidate assessment items and
rubrics. Everything before it produced *proposals about a learner* — an explanation, a suggested
probe — read once and discarded. An assessment item is different in kind. It persists, it is shown
to many learners, and answers to it become **evidence**, which the deterministic engines convert
into mastery.

That is the risk. A bad tutor explanation confuses one learner for one minute. A bad assessment item
— ambiguous, mis-keyed, or testing something other than the objective it claims — produces evidence
that is *wrong in a way nothing downstream can detect*. The mastery engine faithfully computes a
score from it. The progression policy faithfully unlocks or withholds the next skill. Every control
MVP-0 built works exactly as designed and yields a wrong answer, because the measurement was wrong.

Doc 03 §4 names the trust states — `UNVERIFIED` for fresh generated content, `VERIFIED_CONTENT`
after approved validation or review, `REJECTED` for content that failed. What it does not specify is
what promotion *requires*, and that is what this decides.

## Decision

Generated content enters at `UNVERIFIED` and reaches `VERIFIED_CONTENT` only by passing a staged
pipeline, in order:

```
        AI-generated assessment content
                     ↓
                UNVERIFIED
                     ↓
        Structural / schema validation
                     ↓
        Deterministic policy validation
                     ↓
        Quality / safety validation
                     ↓
     Human approval when policy requires
                     ↓
                 VERIFIED
                     ↓
      Eligible for permitted learner use
```

### Properties of the pipeline

**The stages are ordered and each may reject.** A stage that rejects ends the pipeline; content
becomes `REJECTED` and does not fall through to a later stage. Ordering is cheapest-first, so a
malformed item never consumes quality review, and the stage that rejected is recorded.

**No stage promotes on its own.** Promotion to `VERIFIED_CONTENT` is the *outcome of the whole
pipeline*, never the act of an individual validator. There is no code path that writes
`VERIFIED_CONTENT` directly, and trust state is never an argument a generator or a caller supplies.

**Passing automated validation is not approval.** Content that survives the first three stages has
*failed to be rejected*, which is a weaker statement than having been approved. Whether it advances
past that point is the policy question below.

### The approval policy for MVP-1

"When policy requires" is only meaningful if the policy is written down, so:

> **For MVP-1, human approval is required for any content that will be used in a scored context** —
> anything whose answers can become evidence. There is no automated path to `VERIFIED_CONTENT` for
> such content.

The reason is that the decisive property is not checkable from the artifact. Whether an item measures
the objective it *claims* to measure is a question about curriculum intent, not about the item's
text. A well-formed, unambiguous, correctly-keyed item that tests the wrong concept passes every
automated stage and corrupts the evidence for the skill it was filed under. Only somebody who knows
what the objective means catches that.

There is a second reason, about how rules erode. "A human approved this" has no gradient — it either
happened or it did not. "It passed the checks" invites a follow-up about *which* checks, and that
answer tends to get shorter under delivery pressure.

The policy is deliberately expressed as policy rather than as an unconditional rule, because
non-scored uses exist and will grow — preview, authoring assistance, reviewer suggestions — and
those do not need the same gate. Relaxing it for scored content is a decision that requires its own
ADR, not a configuration change.

### What VERIFIED does and does not mean

`VERIFIED_CONTENT` says: *this content is fit to put in front of a learner in the contexts the policy
permits.* It says nothing about AI's authority over learners.

```
   AI-generated content VERIFIED
                ≠
   AI evaluation authoritative

        MVP-1 AI evaluation
              remains
          FORMATIVE_ONLY
```

[M1-ADR-010](M1-ADR-010-assessment-evaluation-is-formative-only.md) holds unchanged: AI evaluation of
a learner's answer is `FORMATIVE_ONLY` and can never create scored evidence, whatever the trust state
of the content being answered. A verified item answered by a learner produces evidence through the
deterministic scoring engine, not through an agent's opinion of the answer.

Together the two ADRs close both routes by which a model could influence a learner's record — by
grading, and by writing what the learner is graded on. Either alone leaves the other open.

## Alternatives considered

**Promote on unanimous automated validation.** Rejected: the decisive property — does this item
measure its objective — is not derivable from the item, so more validators do not converge on it.

**Promote after N learner responses look statistically sane.** Genuinely useful later as a
*demotion* signal. Rejected as a promotion mechanism because gathering the statistics requires
serving unverified content in a scored context, which is what the pipeline forbids, and because the
evidence is already corrupted by the time the statistics say so.

**Promote automatically, flag for retrospective review.** The version most likely to be adopted under
delivery pressure and the worst of the set. Retrospective review means discovering a bad item after
it has moved learners' mastery, and MVP-0's ledger is append-only by design — the evidence cannot
simply be deleted.

**A second model reviews the first.** Two models sharing a failure mode is not independent review. It
is a reasonable *quality/safety* stage that may reject; it does not become approval by being a model.

**Make human approval unconditional rather than policy-driven.** Simpler to state and simpler to
enforce. Rejected because it puts non-scored uses — preview, authoring assistance — behind a review
queue that protects nothing, which is the kind of friction that eventually gets removed wholesale
rather than carefully.

## Consequences

- M1-T10 delivers generation, the staged validation pipeline, and a review queue. It does not deliver
  an end-to-end content path: nothing reaches a scored assessment without a person.
- Review throughput becomes the constraint on how fast generated content becomes usable in scored
  contexts. That is the intended trade against an evidence ledger that cannot be un-written.
- The stage that rejected content must be recorded, not just the fact of rejection. "Rejected" alone
  tells a content author nothing about what to fix, and tells an operator nothing about whether the
  generator or the curriculum is drifting.
- The admin audit trail from MVP-0 (V013) is the natural home for promotion records — reviewer, item
  version, timestamp — rather than a new mechanism.
- Trust state must be enforced where content is *selected* for an attempt, not only at promotion. The
  filter applied when building an attempt is the control; the state on the row is the data it reads.

## Verification

These are the assertions M1-T10 must carry:

- Generated content is persisted as `UNVERIFIED`, and no code path accepts a caller-supplied trust
  state on creation.
- Each pipeline stage can reject, and a rejection records which stage rejected.
- No stage, and no combination of stages, transitions content to `VERIFIED_CONTENT` without the
  approval the policy requires for that content's intended use.
- Promotion of scored-context content requires an authenticated author or administrator and writes an
  audit record naming the reviewer and the item version.
- Selecting items for a diagnostic attempt excludes anything not `VERIFIED_CONTENT`, asserted with
  `UNVERIFIED` content present in the database.
- Answers to `UNVERIFIED` content cannot produce evidence — asserted by attempting it and observing
  the refusal, not by the absence of a code path.
- A `VERIFIED_CONTENT` item answered by a learner still produces evidence through the deterministic
  engine, and AI evaluation of that answer remains `FORMATIVE_ONLY` (M1-ADR-010).
