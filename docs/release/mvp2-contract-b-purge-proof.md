# Contract B purge mechanism — executable proof evidence

- **Result:** **PASS** — 8/8 behaviours proven, 5/5 negative controls caught.
- **Satisfies:** M2-ADR-017 §6 prerequisite 5, per the resolution in M2-ADR-019 §6.
- **Decided by:** M2-ADR-019. This proof demonstrates the semantics that ADR decided; it does not
  decide them.
- **Proof:** [`scripts/validation/contract-b-purge-proof.py`](../../scripts/validation/contract-b-purge-proof.py)

> **This is not production compliance and must not be reported as such.** It qualifies purge
> *semantics* against a throwaway schema so `V037` ships behaviour that has been executed rather
> than described. `V037`'s own tests replace it (M2-ADR-019 Consequences).

## Why a proof rather than a document

M2-ADR-017 §6 prerequisite 5 requires the purge mechanism to *"exist and be testable"*, citing
`V023` — a policy with no mechanism is *"a comment pretending to be a control."* M2-ADR-019 §6 found
that requirement literally unsatisfiable: `V023`'s precedent creates the purge function *inside* the
migration, so the mechanism prerequisite 5 gates on lives in `V037`, which prerequisite 5 gates.

The resolution was an executable proof against an isolated schema — stronger than documentation,
which `V023`'s own wording forecloses, and achievable, which production code before its own
migration is not.

## Isolation

A disposable PostgreSQL container built from the project's own image (PostgreSQL 18.1), a throwaway
`proof` schema, torn down at the end of the run. **No RAMALS database was contacted** — not
production, not the T15 qualification cluster, not a developer database. No migration and no
repository database state was created.

Supplying the image's own environment lets it create the **real role names** — `ramals_core_runtime`,
`ramals_ai_runtime`, `ramals_core_migration` — so the access matrix is proven against the names
M2-ADR-018 §3 actually governs rather than invented stand-ins. Only the reporting/analytics role,
which does not exist yet, is represented by a stand-in.

## The eight behaviours

| # | Behaviour | Result | Observed |
| --- | --- | --- | --- |
| 1 | Restricted content exists and is readable before purge | PASS | 4 result rows; payload readable |
| 2 | Adoption purge removes the complete result row | PASS | 1 removed; 0 remaining |
| 3 | Identity, provider ids, digest, usage, cost and purge ledger survive | PASS | `custom_id`, `msgbatch_…`, 64-char digest, 16/4 tokens, cost, 1 ledger entry |
| 4 | Ceiling sweep removes only eligible terminal results beyond the window | PASS | 1 swept; expired gone; in-window row kept |
| 5 | A non-terminal execution's result is never purged, however old | PASS | live row survives at 45 days, state `RUNNING` |
| 6 | Repeated purge is idempotent and writes no second ledger claim | PASS | second calls removed 0; ledger still 1 |
| 7 | Only the runtime role holds write grants; sweep rejects a below-floor window | PASS | grants = `ramals_core_runtime:DELETE,INSERT` only; `retention_days=0` rejected |
| 8 | Purged material is unreconstructable from any surviving surface | PASS | canary absent from all surviving tables and the ledger; 0 residue |

Behaviour 5 is the one M2-ADR-019 §7 singles out as most likely to be skipped and most expensive to
get wrong: it is the difference between a retention control and a data-loss bug. The fixture makes a
live execution's result 45 days old — well beyond the ceiling — so an age-only sweep deletes it.

## Negative controls

A suite that only ever sees a correct mechanism agrees with any implementation, including a broken
one. Each mutation breaks exactly one decided behaviour, and the control asserts the corresponding
proof turns red.

| Mutation | Must fail | Caught |
| --- | --- | --- |
| Sweep drops the terminal-state test | 5 | ✅ |
| Adoption purge nulls the payload instead of deleting the row | 2 | ✅ |
| Purge cascades into the provider execution row | 3 | ✅ |
| Ledger entry written unconditionally | 6 | ✅ |
| Reporting role granted `SELECT` + `DELETE` on the result table | 7 | ✅ |

**A control failed during development, and the failure was correct.** The reporting-grant mutation
was initially *missed*: proof 7 probed authorization by attempting a `DELETE`, which was denied —
but denied because the role lacked `SELECT` for the `WHERE` clause, not because it lacked `DELETE`.
The assertion passed for the wrong reason and would have let a stray `DELETE` grant through. Proof 7
now asserts the grant matrix directly from `information_schema.role_table_grants`, and the control
catches the mutation.

## Semantics confirmed, as decided by M2-ADR-019

**Purged** — the `ai_execution_result` row entire, including the ciphertext payload,
`encryption_key_id`, envelope metadata and `result_digest`. Not nulled, not tombstoned.

**Retained** — `request_id`, `provider_execution_id` (`msgbatch_…`), `custom_id`, lifecycle state,
usage and cost, `response_digest`, all timestamps, and the transition-ledger entry recording which
path removed the row. Derived from M2-ADR-018 §6, which classifies these Internal precisely so
reconciliation and cost evidence remain usable; removing `custom_id` in particular would make a
duplicate provider execution undetectable.

**Two mechanisms, not one grant** — a targeted adoption delete by primary key, and a sweep taking a
window and no row id, so it cannot be asked to delete a specific row and rejects a below-floor
window the way `V023` rejects `retention_days < 1`.

**Crypto-deletion** is not exercised here and is not the mechanism (M2-ADR-019 §5). Row deletion is;
key destruction is its consequence.

## Reproducing

```
python scripts/validation/contract-b-purge-proof.py
```

Requires Docker and the `ramals-deploy-postgres` image. Exit code 0 only when all eight behaviours
pass **and** all five controls are caught. The machine-readable record is written to
`contract-b-purge-proof.json`, which is retained outside the repository following the T15 evidence
precedent.

**No sensitive payload appears in any committed artifact.** The proof uses a synthetic canary token
in place of restricted model output; the canary is absent from the machine-readable record and from
this document, and behaviour 8 asserts its absence from every surviving database surface.

## Status

**M2-ADR-017 §6 prerequisite 5: SATISFIED** for the MVP/research environment.

Remaining M2-ADR-018 preconditions, none of which this proof addresses:

| Criterion | Status |
| --- | --- |
| 3 — `ResultEncryptionKeyProvider` port, no vendor KMS | **Open** |
| 4 — AES-256-GCM envelope, moved-ciphertext test | **Open** |
| 5 — fail-closed behaviour proven for every row of §10 | **Open** |
| 9 — no plaintext in logs on any path | **Open** |
| 6, 7, 8 | Reclassified by M2-ADR-019 §6 as `V037` completion criteria |

`V037` remains **BLOCKED** on criteria 3, 4, 5 and 9.
