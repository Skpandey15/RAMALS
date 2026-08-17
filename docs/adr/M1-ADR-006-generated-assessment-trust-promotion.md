# M1-ADR-006: Generated assessment content is UNVERIFIED until a human promotes it

- **Status:** Accepted
- **Date:** 2026-08-17
- **Relates to:** MVP-1 Canonical Package v1.3 Doc 03 §4, Doc 07 §2–§3, M1-ADR-010
- **Required before:** M1-T10

## Context

M1-T10 is where AI-generated content first enters the platform: candidate assessment items and
rubrics. Everything before it produced *proposals about a learner* — an explanation, a suggested
probe — which are read once and discarded. An assessment item is different in kind. It persists, it
is shown to many learners, and answers to it become **evidence**, which the deterministic engines
convert into mastery.

That is the whole risk. A bad tutor explanation confuses one learner for one minute. A bad
assessment item — ambiguous, mis-keyed, testing something other than the objective it claims —
produces evidence that is *wrong in a way nothing downstream can detect*. The mastery engine will
faithfully compute a mastery score from it. The progression policy will faithfully unlock or withhold
the next skill. Every control MVP-0 built works perfectly and produces a wrong answer, because the
measurement itself was wrong.

Doc 03 §4 names the trust states — `UNVERIFIED` for fresh generated content, `VERIFIED_CONTENT` for
content promoted after approved validation or review, `REJECTED` for content that failed. What it
does not say is what promotion *requires*, and that is the decision.

## Decision

**Generated assessment content is `UNVERIFIED` on creation and can only become `VERIFIED_CONTENT`
through explicit human approval. No automated check promotes content.**

- Generation writes candidate items and rubrics at `UNVERIFIED`. There is no path that writes
  `VERIFIED_CONTENT` directly, and the trust state is never an argument the generator supplies.
- Automated validation — schema, semantic, security, duplicate detection, answer-key sanity — can
  **reject**, and can *inform* a reviewer. It cannot promote. A proposal that passes every automated
  check is a proposal that has failed to be rejected, which is not the same as having been approved.
- Promotion is an authenticated action by a human with the content-author or administrator role,
  recorded in the audit trail with the reviewer's identity, the item version and the moment.
- **`UNVERIFIED` content is never served to a learner in a scored context.** It cannot appear in a
  diagnostic attempt, and answers to it cannot become evidence.

### Why automated promotion is refused

The tempting argument is that a sufficiently good validator makes review redundant — and for
schema and security it does. The reason it fails for assessment content is that the property that
matters is not checkable from the artifact.

Whether an item actually measures the objective it claims to measure is a question about the
curriculum's intent, not about the item's text. A well-formed, unambiguous, correctly-keyed item
that tests the wrong concept passes every automated check available and corrupts the evidence for
the skill it was filed under. The only thing that catches it is somebody who knows what the
objective means.

There is a second reason, about failure modes rather than correctness. An automated promotion path
is a path that can be widened. The rule "a human approved this" has no gradient — it either happened
or it did not. "It passed the checks" invites a follow-up question about which checks, and the answer
tends to get shorter over time.

### What automated validation is for

Rejecting cheaply, and making review possible. A reviewer looking at a queue where obvious failures
have already been removed reviews better than one wading through them. Automated checks earn their
place by *reducing what reaches a human*, never by replacing the human.

### Relationship to M1-ADR-010

[M1-ADR-010](M1-ADR-010-assessment-evaluation-is-formative-only.md) says AI *evaluation* of a
learner's answer is `FORMATIVE_ONLY` and can never create scored evidence. This ADR is the other
half: AI *generation* of assessment content cannot create scored-context content without review.

Together they close both routes by which a model could influence a learner's record — by grading, or
by writing what the learner is graded on. Either alone leaves the other open.

## Alternatives considered

**Promote on unanimous automated validation.** Rejected above: the decisive property — does this
item measure its objective — is not derivable from the item.

**Promote after N learner responses look statistically sane.** Attractive, and genuinely useful
later as a *demotion* signal. Rejected as a promotion mechanism because it requires serving
unverified items in a scored context to gather the statistics, which is precisely what this ADR
forbids. It also inverts the harm: the evidence is already corrupted by the time the statistics say
so.

**Promote automatically, flag for retrospective review.** The version most likely to be adopted
under delivery pressure, and the worst. Retrospective review of content already generating evidence
means discovering a bad item after it has moved learners' mastery, and MVP-0's ledger is append-only
by design — the evidence cannot simply be deleted.

**A second model reviews the first.** Two models sharing a failure mode is not independent review.
Useful as another automated check that can *reject*; it does not become approval by being a model.

## Consequences

- M1-T10 delivers generation and validation, not an end-to-end content pipeline. Content reaches a
  review queue; nothing appears in an assessment without a person.
- Review throughput becomes the constraint on how fast generated content is usable. That is the
  intended trade: the alternative is faster content of unknown quality feeding an evidence ledger
  that cannot be un-written.
- The admin audit trail from MVP-0 (V013) is the natural home for promotion records — reviewer,
  item version, timestamp — rather than a new mechanism.
- Trust state must be enforced at the point content is *selected* for an attempt, not only at
  promotion. A filter applied when building an attempt is the control; the state on the row is the
  data it reads.

## Verification

These are the assertions M1-T10 must carry:

- Generated content is persisted as `UNVERIFIED`, and no code path accepts a caller-supplied trust
  state on creation.
- No automated validator, in any combination, transitions content to `VERIFIED_CONTENT`.
- Promotion requires an authenticated author or administrator, and writes an audit record naming the
  reviewer and the item version.
- Selecting items for a diagnostic attempt excludes anything not `VERIFIED_CONTENT`, asserted with
  `UNVERIFIED` content present in the database.
- Answers to `UNVERIFIED` content cannot produce evidence — asserted by attempting it and observing
  the refusal, not by the absence of a code path.
