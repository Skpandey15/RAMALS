# MVP-1 agent integration contract — Diagnostic, Assessment, Adaptation

**Status: proposed. Not implemented.** This answers the seven questions for each capability so the
decisions can be reviewed before code is written, because two of the three need an architectural
decision rather than wiring.

M1-T18 established the situation this resolves: the AI plane deploys, authenticates and reports all
four agents as `NON_AUTHORITATIVE`, and three of the four have no path from a learner request.
Tutor's gap was an absent controller and is fixed. These three are not the same shape as each other,
and only one of them is a missing adapter.

## Summary of what is actually missing

| Capability | Port | Client | Service | Gate | What is missing |
| --- | --- | --- | --- | --- | --- |
| **Adaptation** | ✅ | ✅ | ✅ | ✅ consumed by the service | **one caller.** `RecommendationService.recommend()` computes the deterministic decision and never asks the agent to compare. |
| **Assessment** | ✅ | ✅ | intake service | — | **an invocation point.** `AssessmentCandidateIntakeService` exists and is the only writer of `ai_execution`, but no controller reaches it. |
| **Diagnostic** | **absent** | **absent** | — | ✅ unconsumed | **the flow it proposes into.** The agent proposes which objective to probe next; the diagnostic serves a fixed item set and has no next-item step to propose into. |

---

## Adaptation

**1. Invoking event.** A learner reads their recommendations —
`GET /api/v1/me/recommendations`, and the internal recommendation refresh after a diagnostic
submission. Both already flow through one place.

**2. Owner.** `RecommendationService.recommend()`. It calls `policy.decide(snapshot)` at line 41 and
returns; the agent comparison belongs immediately after, on the decision that has already been made.

**3. Proposal.** `AdaptationProposalGate.Proposal(skillCode, recommendedAction)` — the agent's view of
what the learner should do next.

**4. Deterministic gate.** `AdaptationProposalGate.compare(deterministicDecision, proposal)`, which
already exists and already returns `Result(deterministicDecision, disagreement)`. Its contract is
explicit: *"returns that decision in every case, including an invalid or missing AI action."*

**5. Authority.** `RecommendationService` — and it **does not change its answer**. The deterministic
decision is returned to the learner whether the agent agrees or not. The agent's output is research
and observability input; `disagreement` is a metric, not a branch.

**6. Durable evidence.** An `ai_execution` row per comparison via `AiExecutionRecorder`, carrying
`agentRunId`, `promptTemplateId`, `promptVersion`, `modelRoute`, `trustLevel` and the disagreement
outcome. This is the first learner-journey write of `ai_execution`; today the only writer is the
authoring intake path.

**7. E2E proof.** A learner fetches recommendations against a stubbed `AdaptationPort` that proposes
a *different* action from the deterministic policy, and the test asserts: the response is the
deterministic recommendation unchanged, an `ai_execution` row exists for the run, and the
disagreement counter incremented. Failing that test before the change is the point — today the port
is never called at all.

**Decision needed: one, about where the call sits relative to the transaction.**

`recommend()` is `@Transactional`, and its only caller — `DiagnosticSubmissionService.submit()` — is
transactional too. So the entire diagnostic submission is one transaction, and putting the agent call
inside it would hold a database connection open across a network call with a 12-second deadline.
Under load that is connection-pool exhaustion, and it is the exact failure `TutorService` was written
to avoid: *"Each repository call manages its own transaction and releases the connection before
returning, so nothing is held open across the network call below."*

The comparison therefore has to happen **after commit** — either a
`@TransactionalEventListener(phase = AFTER_COMMIT)` on a recommendation-decided event, or a call from
above the transactional boundary. Both are sound; both change failure semantics in a way that should
be stated rather than discovered: **an agent failure after commit cannot roll back the submission,
and must not.** The learner's evidence, mastery and recommendation are already durable and correct;
the agent comparison is research input arriving afterwards.

That is the right behaviour, and it is a decision rather than a detail, because it determines whether
a failed comparison is invisible to the learner (it should be) and whether `ai_execution` can be
written in the same transaction as the decision it describes (it cannot).

---

## Assessment

**1. Invoking event.** A content author submits a candidate assessment item for intake. This is an
authoring action, not a learner action.

**2. Owner.** `AssessmentCandidateIntakeService`, which exists and already records `ai_execution`.
What is absent is a controller — `AdminContentController` is the natural home.

**3. Proposal.** An evaluated assessment candidate: the agent's judgement of an item's quality and
alignment.

**4. Deterministic gate.** `AssessmentCandidatePersistenceService` and the existing S0-07 provenance
intake path, which records candidate provenance rather than trusting the proposal.

**5. Authority.** The content-approval workflow. A proposal is a candidate; publication remains an
explicit human approval through `ApprovalRequestController`.

**6. Durable evidence.** Already implemented: `ai_execution` plus the V018 provenance chain.

**7. E2E proof.** An authenticated content author posts a candidate, and the test asserts the
candidate is persisted with provenance, an `ai_execution` row is written, and the item is **not**
published without approval.

**Decision needed: one, and it is about scope rather than design.** Is authoring-side assessment
intake part of the MVP-1 release surface at all? T18's canonical journey is
`learner → … → tutor/assessment → adaptation`, which reads as a *learner-facing* assessment step —
but `AssessmentPort` feeds authoring, not the learner journey. Either:

- **(a)** the journey means the existing deterministic diagnostic/assessment submission, which
  already works end to end and needs no agent, and Assessment intake is an authoring feature that
  T18 should validate separately; or
- **(b)** MVP-1 intends an agent-evaluated learner submission, which does not exist in any form and
  is materially more than an integration.

I read the evidence as **(a)**. The deterministic assessment path is complete and passing, and
nothing in the code suggests a learner-facing assessment agent was ever built.

---

## Diagnostic

**1. Invoking event.** There is no event. This is the finding.

`DiagnosticProposalGate` documents the agent's role precisely: *"The agent proposes which objective
to probe next."* The diagnostic flow has three operations — create an attempt, fetch its items,
submit them — and serves a **fixed item set** chosen when the attempt is created. There is no
next-item step, no endpoint that serves one, and therefore nothing for a next-objective proposal to
be proposed into.

**2–6.** Unanswerable until (1) is decided. There is also no `DiagnosticPort` and no
`RamalsAiDiagnosticClient`: unlike Adaptation, the adapter layer does not exist either.

**7.** An E2E test cannot be written for a capability with no invocation point.

**Decision needed, and it is architectural.** Integrating Diagnostic means making the diagnostic
adaptive — an attempt that serves one objective at a time and asks the agent what to probe next,
with the gate refusing proposals that violate prerequisites or misread "we do not know yet" as
failure. That is a product change to the assessment flow, new endpoints, new attempt state, and
almost certainly a migration. It is not wiring.

The gate having been written first is consistent with that: the authority-refusing half was built
before the flow that would need it.

---

## What I recommend, and what I have not done

**Implement Adaptation first**, once the after-commit placement above is confirmed. It is the only
one of the three whose pieces all exist, and it is what makes `ai_execution` reachable from the
learner journey, which T18 item 6 requires. I did not write it unilaterally because the transaction
boundary is a real decision with a visible consequence, not a detail.

**Confirm Assessment as (a)** — authoring-side, validated separately from the learner journey. If so,
adding the intake controller is small; if not, we are discussing a feature that does not exist.

**Do not implement Diagnostic in this cycle.** It needs an accepted ADR for an adaptive diagnostic
flow before any code is written. The project's operating rule is explicit that no implementation task
may start while a decision it requires is open, and `Mvp1ReleaseBoardTests` enforces it for tasks
that name an ADR.

**Consequence for T18 that has to be said plainly:** if the canonical journey is read strictly as
`… → tutor/assessment → adaptation → persisted execution evidence`, then with Adaptation implemented
and Diagnostic deferred, T18 can reach `Outcome: PASS` only if the journey's "diagnostic" step means
the deterministic diagnostic that already works — which is what it has always meant in the passing
drill. If it is read as requiring the Diagnostic *agent*, T18 cannot pass this cycle, and the honest
sequence is an ADR first.

That reading is the decision I need from you, because it determines whether this is one PR or a
phase.
