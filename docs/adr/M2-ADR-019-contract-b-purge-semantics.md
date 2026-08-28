# M2-ADR-019: Contract B purge semantics, and the ordering defect in prerequisite 5

- **Status:** Accepted — 2026-08-27. Recorded 2026-08-28: the status line was left at Proposed when prerequisite 5 was declared satisfied, which acceptance criterion 1 below requires. Superseded in one respect by `V037`, which ships the production mechanism the §7 proof qualified.
- **Decides:** what Contract B purge does, what survives it, who may invoke it, how it behaves under
  concurrency, and what "the purge mechanism exists and is testable" can mean before `V037` exists.
- **Relates to:** M2-ADR-017 §6 prerequisite 5 (which this unblocks), M2-ADR-018 §3/§6/§8/§9 and its
  acceptance criteria 6–8, `V021`, `V022`, `V023`.
- **Originates here**, on the same basis as M2-ADR-016 through M2-ADR-018.

## Context

M2-ADR-017 §6 prerequisite 5 requires that *"the purge mechanism exists and is testable, both the
on-adoption delete and the ceiling sweep"*, citing `V023`: a policy with no mechanism is *"a comment
pretending to be a control."*

Attempting to satisfy it surfaced one ordering defect and five unspecified behaviours. Writing the
mechanism without deciding these first would mean guessing at semantics and then presenting the
guess as compliance.

### The ordering defect

`V023` is the named precedent, and what `V023` actually did is create a PL/pgSQL function **inside
the migration**: `core.purge_expired_ai_executions(retention_days INTEGER DEFAULT 400)`, which
rejects `retention_days < 1` and returns a row count. The Contract B analogue is a function inside
`V037`.

So prerequisite 5 gates `V037`, and the mechanism it demands lives *in* `V037`. Read literally, it
cannot be satisfied and `V037` is permanently blocked.

M2-ADR-018's acceptance criteria have the same shape. They are introduced as *"`V037` may proceed
when all of the following are true"*, yet:

- criterion 6 reads *"`V037` grants exactly the access matrix of §3, with a migration-contract
  test"* — a statement about `V037`'s own content;
- criterion 7 requires a test that the result row is deleted in the adoption transaction — a table
  `V037` creates;
- criterion 8 requires the purge function to exist and be invokable — the function `V037` creates.

**These are not gates on `V037`. They are `V037`'s own definition of done**, mislabelled as
preconditions. That is the defect, and it is worth fixing rather than routing around.

### The five unspecified behaviours

M2-ADR-018 §9 specifies delete-on-adoption, the 30-day ceiling, operator-invoked ownership,
non-append-only storage and observability. It does **not** specify: what purge does beyond the
result table; who may invoke a sweep versus an adoption delete; how purge behaves against an
execution that is still running; whether destroying key material is itself a purge mechanism; or
what makes a repeated purge safe.

## Decision

### 1. Purge scope — one row, and nothing else

**Purgeable: the `core.ai_execution_result` row, entire.** That row is the only place RESTRICTED
content lives, and purge deletes the whole row rather than nulling a column. The encrypted
`normalized_result`, its `encryption_key_id`, the envelope metadata and the `result_digest` all go
with it, because a row that survives with its body removed is a row whose absence of content must
then be explained.

**Not purgeable, and deliberately so:**

| Retained | Why |
| --- | --- |
| `request_id` — execution identity | Proves the execution occurred |
| `provider_execution_id` (`msgbatch_…`) | M2-ADR-018 §6 classifies it Internal. It is what the reconciliation sweep matches on |
| `custom_id` and correlation identifiers | Same. Removing them would make a duplicate provider execution undetectable — the one guarantee Contract B actually claims |
| `provider_message_id`, `provider_request_id` | Internal; support correlation |
| Usage counts, estimated cost, model, route | The cost-evidence scenario counts these. Purging them would destroy the evidence that distinguishes Contract B from a bigger schema |
| Lifecycle states, fences, transition ledger | Forensic record of what happened |
| All timestamps, plus the purge timestamp and reason | How an auditor reconstructs the execution without its content |
| `core.ai_execution.response_digest` (`V036`) | SHA-256, not reversible; proves *what* was returned without retaining it |

This is derived from M2-ADR-018 §6 rather than chosen here. That section already classifies the
identifiers and cost metadata as Internal, holds them unencrypted, and states that encrypting them
*"would be actively harmful"* because reconciliation and cost evidence cannot operate over
ciphertext. Purging them would be the same harm by a different route.

**The invariant:** after purge, an auditor can prove an execution occurred, when, against which
provider execution, at what cost, and that its result was purged — and cannot recover what the
model said.

### 2. Purge state machine, and idempotency

A result row is in exactly one of three states, and only the first is a row at all:

```
PRESENT ──adoption commits──▶ PURGED_ON_ADOPTION
        └─ceiling reached───▶ PURGED_ON_CEILING
```

Both terminal states are represented the same way: **the row is gone, and a transition-ledger entry
records which path removed it, when, and under what retention window.** The ledger entry is the
durable evidence; the absence of the row is not self-describing.

**Idempotency is structural, not defensive.** Purge is a `DELETE` matched on primary key or on a
ceiling predicate, so a second invocation affects zero rows and returns zero. There is no state to
check first and no error to raise: purging an already-purged result is a no-op that reports
honestly as one. A ledger entry is written only when a row was actually removed, so repeated sweeps
do not manufacture repeated evidence.

### 3. Authorization — two mechanisms, not one grant

M2-ADR-018 §3 grants `DELETE` on the result table to `ramals_core_runtime` and to nobody else. That
is necessary for delete-on-adoption and insufficient as an authorization boundary, because it does
not distinguish the two things that delete rows.

**Delete-on-adoption** is an ordinary-path operation: a targeted `DELETE` by primary key, inside the
adoption transaction, deleting exactly the row whose outcome was just committed. It can remove one
row and only the row the caller just adopted.

**The ceiling sweep** is not an ordinary-path operation. It is a `SECURITY INVOKER` function taking
an explicit retention window, following `V023`'s signature precedent, and:

- it **cannot be asked to delete an arbitrary row** — it has no row-id parameter, only a window;
- it **rejects a window below the ceiling floor**, the way `V023` rejects `retention_days < 1`,
  so it cannot be turned into "delete everything" by passing `0`;
- it is invoked by an operator or a job, never from an agent, provider, API or workflow path.

**No agent, provider adapter or Contract-B execution path may invoke the sweep.** The AI plane has
no database access at all (M2-ADR-012, enforced by `test_no_database_access.py`), so it cannot
reach either mechanism; the platform runtime can reach the sweep function but no ordinary code path
calls it, and that is a testable property rather than a convention.

### 4. Concurrency — purge must never race an execution that is still live

**The sweep may only remove a result whose execution has reached a terminal state, or whose row has
passed the ceiling.** A result belonging to an execution still `RUNNING`, `RECONCILING` or awaiting
adoption is not eligible however old the row is, because deleting it would destroy the artifact a
reconciliation worker is about to adopt and turn a recoverable execution into an unexplained one.

The ceiling and the terminal-state test are both required, not alternatives: the ceiling bounds
exposure, the terminal-state test bounds damage.

**Delete-on-adoption cannot race the sweep**, because it runs inside the adoption transaction and
takes the row lock the sweep would need. Whichever arrives second finds no row and reports zero,
which is the idempotent outcome of §2 rather than an error.

**A sweep must not hold a long transaction across the whole table.** It deletes in bounded batches
so it cannot block adoption while it runs — an availability property, and the reason a naive
`DELETE … WHERE age > ceiling` over a large table is not the mechanism.

### 5. Cryptographic deletion — a backstop, not the mechanism

**Destroying key material is not the purge mechanism.** Row deletion is. Three reasons:

1. **Granularity.** A key protects every row encrypted under it. Destroying it to purge one result
   destroys results that are still needed, including ones an adoption is about to consume.
2. **Verifiability.** "The row is gone" is checkable at row granularity. "The key is gone, so the
   ciphertext is unreadable" is an argument about key custody, and a weaker claim to hand an
   auditor.
3. **It is unnecessary.** M2-ADR-018's 30-day ceiling already bounds how long any ciphertext exists.

**Key destruction remains the backstop M2-ADR-018 §8 already assigns it.** That section states that
retired key material *"must remain available until no row references it… and is then removed"*, and
that a compromise is handled by *"purging the affected rows, not re-encrypting them."* This ADR adds
one thing consistent with both: **once the last row referencing a retired key is purged, destroying
that key material is required rather than optional**, and is recorded. Retaining a key with nothing
left to decrypt is pure liability.

So crypto-deletion is a *consequence* of purge, never a substitute for it.

### 6. Resolving the ordering defect

**Prerequisite 5's "exists and is testable" is satisfied, before `V037`, by an executable proof of
the purge semantics against an isolated throwaway schema — not by documentation, and not by
production code.**

This is an interpretation of a requirement that is otherwise unsatisfiable, and it is deliberately
*stronger* than the alternative readings rather than weaker:

- **Documentation-only would weaken it.** `V023`'s precedent is explicitly that a policy without a
  mechanism is a comment pretending to be a control. A design document is that comment.
- **Production code is impossible** without `V037`, which prerequisite 5 gates. Requiring it makes
  the prerequisite unsatisfiable, which is the defect rather than a standard.
- **An executable proof is achievable and meaningful**: the purge SQL, run against a scratch schema
  shaped like `V037`'s result table, proving every behaviour in §7 — so `V037` ships semantics that
  have been executed rather than semantics that have been described.

**M2-ADR-018 criteria 6, 7 and 8 are hereby reclassified as `V037` completion criteria rather than
`V037` preconditions.** They describe `V037`'s own content — its grants, its adoption transaction,
its purge function — and no ordering can make them true beforehand. Nothing is removed and no bar is
lowered: they must still all hold, and `V037` is not complete until they do. Only the label changes,
from a gate that cannot be passed to a definition of done that can.

Criteria 3, 4, 5 and 9 are unaffected. They concern the key provider port, the envelope format,
fail-closed behaviour and log hygiene, none of which requires the tables to exist, and all of which
remain genuine preconditions.

### 7. What the executable proof must demonstrate

Eight behaviours, each of which must fail if the mechanism is wrong:

| # | Proof |
| --- | --- |
| 1 | **Sensitive content existed before purge** — a decryptable result is present and readable through the intended path. A test that purges nothing proves nothing |
| 2 | **Purge runs through the intended controlled mechanism** — the adoption delete and the sweep function, not an ad-hoc `DELETE` written by the test |
| 3 | **Sensitive content is unrecoverable afterwards** — no row, no ciphertext, no plaintext, by any query available to the runtime role |
| 4 | **Required audit metadata survives** — execution identity, provider execution id, `custom_id`, usage and cost, lifecycle, timestamps, and the purge record itself |
| 5 | **Repeated purge is safe** — a second invocation removes zero rows, raises nothing, and writes no second ledger entry |
| 6 | **Unauthorized purge is rejected** — a role without the grant cannot delete, and the sweep rejects a below-floor window rather than deleting everything |
| 7 | **A non-terminal execution is not purged** — a result belonging to a live execution survives a sweep that would otherwise be eligible on age |
| 8 | **No path reconstructs the payload** — logs, audit records and error messages on both success and failure carry identity, never content |

Proof 7 is the one most likely to be skipped and the most expensive to get wrong: it is the
difference between a retention control and a data-loss bug.

## Acceptance criteria for prerequisite 5

Prerequisite 5 is **SATISFIED** when all of the following hold:

1. This ADR is accepted, fixing purge scope, state machine, authorization, concurrency and
   crypto-deletion.
2. An executable proof exists, runnable in isolation without `V037`, demonstrating all eight
   behaviours of §7 against a scratch schema shaped like the Contract B result table.
3. The proof runs in CI or is runnable by a single documented command, and fails loudly when the
   mechanism is wrong — negative controls, not only happy paths.
4. The proof creates no persistent schema, no migration, and no repository database state.

It is **not** satisfied by this ADR alone. This ADR decides *what* the mechanism is; §7's proof
demonstrates that it *works*.

## Alternatives rejected

- **Read prerequisite 5 as documentation-only.** The cheapest resolution and the one `V023`'s own
  wording forecloses. It would make the prerequisite the exact thing it warns against.
- **Delete prerequisite 5, since `V037` will test the purge anyway.** Removes a real control to
  resolve a labelling problem, and loses the property that `V037` ships proven semantics.
- **Build `V037` first and satisfy prerequisite 5 inside it.** Honest about the circularity and
  inverts the gate: the migration would ship before its retention control was ever executed.
- **Purge the provider identifiers and `custom_id` along with the result.** Superficially more
  thorough and directly harmful: it would destroy the reconciliation and cost evidence that
  M2-ADR-018 §6 keeps unencrypted precisely so they remain usable, and would make duplicate
  provider executions undetectable.
- **Use key destruction as the purge mechanism.** Attractive because it looks instantaneous. Too
  coarse to purge one result, and a weaker claim than an absent row.
- **A single `DELETE` grant covering both adoption and sweep.** What M2-ADR-018 §3 currently
  implies, and it leaves no boundary between a targeted delete on an ordinary path and an
  arbitrary bulk delete.

## Consequences

- **Prerequisite 5 becomes satisfiable**, with a defined proof rather than an argument.
- **M2-ADR-018 criteria 6–8 move from preconditions to `V037` completion criteria.** No requirement
  is dropped; `V037` is not complete until all three hold.
- **`V037` must create two distinct purge paths**, not one — a targeted adoption delete and a
  windowed sweep function with a floor.
- **The sweep needs a terminal-state test as well as an age test**, which is more than M2-ADR-018 §9
  currently describes and is the correction that prevents a live execution being purged.
- **Key destruction becomes required** once the last row under a retired key is purged, where
  M2-ADR-018 §8 left it implied.
- **The proof is throwaway.** It qualifies semantics, not the production mechanism, and `V037`'s own
  tests replace it. It must not be presented as production compliance.

## Revisit triggers

- The 30-day ceiling changes, which moves both the sweep window and the rotation simplification.
- A requirement to purge provider identifiers or cost metadata appears — which would contradict
  M2-ADR-018 §6 and reopen the classification rather than adjust this ADR.
- A scheduler arrives, making the sweep a job rather than an operator action.
- `V037` ships, after which the executable proof is superseded by the migration's own tests.
