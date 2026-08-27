# Contract B — amended Definition of Done

- **Status:** Amended 2026-08-27. Supersedes §18 of the Contract B design document for acceptance
  purposes.
- **Satisfies:** M2-ADR-017 §6 prerequisite 2.
- **Authority:** M2-ADR-016 (including Addendum A) and M2-ADR-017. Where this document and the
  design document's §18 disagree, the ADRs decide and this document records the result.
- **Does not unblock:** anything. Prerequisites 3 and 4 — the data-classification sign-off and the
  encryption mechanism — remain open, and `V037` remains blocked on them.

## Why §18 needed amending

The Contract B design document was written before a provider was selected. Its acceptance criteria
assume a provider path that offers documented replay-safe admission, and describe a durable
execution ledger owned by RAMALS-AI. Both assumptions were subsequently tested and neither
survived:

- **No evaluated provider documents replay-safe admission.** Anthropic's Message Batches API
  documents no idempotency key on batch creation, and OpenAI's Responses API documents none on
  `POST /v1/responses` (M2-ADR-016 §3 and Addendum A).
- **Durable state does not live in RAMALS-AI.** M2-ADR-017 §1 keeps the AI plane stateless and puts
  the ledger in Spring/PostgreSQL under Flyway.

Five of the nine original criteria are therefore unachievable or contradicted as written. Leaving
them in place would mean holding Contract B to an acceptance gate that cannot be passed honestly —
and the likely outcome of an unpassable gate is not that the work stops, but that someone declares
it passed.

## The selected provider path, and what it does not provide

**Anthropic Message Batches is the selected Contract B provider path** (M2-ADR-016 Addendum A). It
provides a durable `msgbatch_…` execution identity, status lookup, result retrieval after the
submitting process is gone, `custom_id` correlation, batch enumeration, cancellation, and a
documented 29-day result retention.

**Batch creation has no documented replay-safe admission or idempotency guarantee.** This is a
property of the published provider contract, not a gap in the RAMALS implementation, and no amount
of RAMALS engineering closes it.

It follows that **losing the create acknowledgement opens an ambiguous submission window**: the
batch may or may not exist, and RAMALS does not hold the `msgbatch_…` identity that would settle
the question. The window is bounded in practice by how quickly a created batch becomes visible to
enumeration, and that latency is **undocumented — therefore unbounded by contract**.

## Amended acceptance criteria

| # | Criterion | Status |
| --- | --- | --- |
| 1 | The provider contract independently proves **result retrieval and status lookup**. Replay-safe submission is **not** claimed and is not required for acceptance. | **Amended** |
| 2 | Every logical request maps to **at most one *adopted* provider execution**. A duplicate provider execution is possible under a lost acknowledgement; at most one may ever be adopted as the authoritative RAMALS outcome. | **Amended** |
| 3 | A lost acknowledgement is **reconciled, and any duplicate provider execution is detected and auditable**. Preventing the duplicate is not claimed. | **Amended** |
| 4 | The provider terminal result remains retrievable after process death. | Unchanged |
| 5 | **Spring/PostgreSQL persists the result before adoption**, per M2-ADR-017 §1. RAMALS-AI remains stateless and persists nothing. | **Amended** |
| 6 | Reconciliation workers are durable, fenced and observable. | Unchanged |
| 7 | The crash matrix proves recovery at every external-side-effect boundary, **including the lost-acknowledgement window**. | **Amended** |
| 8 | **Cost evidence accounts for every provider execution attributable to one logical request, and surfaces any duplicate.** Evidence of *no* duplicate is not required; evidence that duplicates are counted and visible is. | **Amended** |
| 9 | Security, performance and operational runbooks are approved. | Unchanged |

Criterion 5 is amended for a different reason than the others: it is superseded by the state
ownership decision in M2-ADR-017, not by the provider's capabilities.

## Reconciliation requirements

**Reconcile by deterministic logical identity.** The `custom_id` submitted with the batch is the
server-derived idempotency key for the request, which is itself derived from the workflow run.
Reconciliation discovers a possible orphan by enumerating provider batches over the admission
window and matching that key — never by position, never by timestamp proximity, and never by a
value a caller supplied.

**Reconciliation must never silently create a replacement execution merely because the original
provider execution identity is unknown.** Not knowing the `msgbatch_…` is precisely the state in
which a resubmission is most likely to duplicate a live execution. An execution whose identity
cannot be established after a bounded reconciliation effort becomes an explicit, operator-visible
terminal state; it does not become a fresh submission.

**Duplicate executions must be detectable and auditable.** Every provider execution attributable to
a request identity is recorded with its usage and cost, so a duplicate appears in the evidence
rather than being inferred from a discrepancy later.

**Contract B must never degrade to Contract A.** An adapter or route that cannot honour durable
recoverable execution fails; it does not fall back to a synchronous single submission. A degraded
execution leaves a row that *looks* recoverable, carries no provider execution identity, and would
be treated by a later recovery worker as retrievable when it is not — which is worse than the
refusal it replaced (M2-ADR-016 §4).

## Qualification requirements

Contract B is not accepted on argument. Qualification must **explicitly exercise the
lost-acknowledgement window** — inducing the loss of a create acknowledgement, and proving that
reconciliation either recovers the original provider execution or reaches an explicit terminal
state, and in neither case silently submits a replacement.

Qualification must record, as durable evidence: the provider execution identity or identities
observed for the logical request, the usage and cost of each, and the reconciliation path taken.
This is the Contract B analogue of the evidence Contract A's S1–S4 produced, and it is held to the
same standard.

## Terminology this document fixes

**RAMALS does not claim strict provider-level exactly-once execution, and must not**, unless a
future provider contract actually supplies documented replay-safe admission *and* that guarantee is
independently qualified against it. Neither condition is met by any evaluated path today.

What Contract B claims, and what qualification must demonstrate, is: **single-intent provider
submission, with detectable duplicate provider execution, and exactly-one authoritative outcome
adoption in RAMALS.** Exactly-once continues to describe business effects, which RAMALS owns
(M2-ADR-003). Nothing stronger than detection describes provider execution, which it does not.

Neither **at-most-once** nor **at-least-once** may be used of provider execution: the first asserts
a duplicate cannot occur, which this provider cannot promise, and the second asserts one always
occurs, which is equally untrue because admission can fail before any submission is made.
