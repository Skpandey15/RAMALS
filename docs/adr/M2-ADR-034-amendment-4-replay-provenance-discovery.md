# M2-ADR-034 Amendment 4 — replay/provenance discovery report

Backing evidence for [Amendment 4](M2-ADR-034-information-gain-probe-selection.md#amendment-4--diagnostic_selection_v6-replayprovenance-correction-2026-09-11).
Records what was actually checked in the repository before the amendment froze a design, so its
conclusions can be re-verified rather than taken on faith. Does not restate the amendment's own
reasoning — see the ADR for that.

## PostgreSQL isolation level

No isolation override exists anywhere in this repository:

- `learning-platform/src/main/resources/application.yml` sets no
  `default_transaction_isolation` and no Hikari/datasource isolation property.
- `DiagnosticService.createAttempt` is annotated `@Transactional` with no `isolation` attribute —
  Spring's `Isolation.DEFAULT` defers to the JDBC driver/database default.
- No `.conf`, Docker, or CI file sets `default_transaction_isolation`.

**Conclusion: `READ COMMITTED`**, PostgreSQL's own out-of-the-box default.

## Active-attempt constraint scope

`V005__assessment_and_attempts.sql`:

```sql
CREATE UNIQUE INDEX uq_assessment_attempt_one_active
  ON core.assessment_attempt (learner_id, assessment_version_id)
  WHERE status = 'IN_PROGRESS';
```

Scoped to `(learner_id, assessment_version_id)`, not to `learner_id` alone. `findActiveAttempt`
(`AssessmentRepository`) queries the identical two-column scope. **A learner can have more than one
`IN_PROGRESS` attempt at once, across different `assessment_version_id`s (different domains).**

## No learner-global serialization

Searched the full `learning-platform/src/main/java` tree for `pg_advisory`, `advisory_lock`, and
`advisory_xact`: zero matches. No `SELECT ... FOR UPDATE` is taken anywhere during attempt
*creation* (`findAttemptForUpdate` exists and is used only during *submission*, an unrelated state
transition, per its own javadoc: "so concurrent submissions serialize on its state transition").
**Nothing serializes one learner's attempt creation across assessment versions.**

## Why `created_at` fails as a replay boundary

`core.assessment_attempt.created_at` is `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
(`V005`), evaluated at `INSERT`-statement execution — i.e. at transaction-start-adjacent time,
strictly before that transaction's eventual commit. PostgreSQL commit visibility under `READ
COMMITTED` is determined by commit order, which is independent of statement-execution order across
concurrent transactions. A transaction that starts (and so fixes a `created_at`) before another,
but commits after it, is ordered *later* than its own `created_at` value would suggest to any
observer reading only that column. No column in `core.assessment_attempt` or
`core.assessment_attempt_item` records commit order — both tables carry only `created_at`
(statement-time) and a `UuidV7`-generated `id` (also allocated pre-commit, at the same
statement-execution moment, carrying the identical defect one layer further from the database).

Executable proof: `AssessmentItemLineagePersistenceIntegrationTests
#concurrentUncommittedAttemptCreatesADestinationExposureCutoffReplayDivergence` (added during PR
#277's own review round), run against a real PostgreSQL 18.1 instance matching this repository's CI
image (`postgres:18.1-alpine`, per `.github/workflows/reusable-backend-ci.yml`). Two independently
controlled JDBC connections/transactions reproduce the exact sequence Amendment 4 §B describes and
assert both halves: the live decision's own exposure read correctly excludes the concurrent,
not-yet-committed attempt's item; a later `created_at`-bounded replay query wrongly includes it.

## Existing provenance capabilities

`core.diagnostic_probe_provenance` (`V055__hypothesis_driven_probe_selection.sql`):

- Records, per row: `attempt_id`, `item_version_id` (the chosen probe), `source_attempt_id`,
  `source_item_version_id`, `source_objective_id`, `relationship_type`, `target_objective_id`,
  `authorizing_relationship_id` — i.e. the complete five-field `DiagnosticHypothesis` identity plus
  the chosen item, for whichever probe actually landed in a packet.
- Immutable: `trg_probe_provenance_guard` rejects `UPDATE`/`DELETE` unconditionally
  (`RAISE EXCEPTION ... USING ERRCODE = '55000'`), and only accepts an `INSERT` while the owning
  attempt is `IN_PROGRESS`.
- Written inside `DiagnosticService.createAttempt`'s own transaction, whenever
  `probeSelection != null` and the chosen item actually appears in the assembled packet — for
  *either* `V6` activation or `V5`'s own fallback resolution, since both paths converge on the same
  `Selection` type and the same insert call.

**This is not new, and it is already immune to the MVCC defect** — it is a direct write of what was
decided, never a later re-derivation from a timestamp. Amendment 4 §S records this finding: exact
*selected-probe* replay is already solved; the actual gap is reproducing *why* `V6` activated or
fell back, i.e. the working set it evaluated.

`core.diagnostic_probe_relationship` (`V054__diagnostic_probe_relationship.sql`): `status IN
('DRAFT', 'PUBLISHED')` only (no retract/supersede state today);
`trg_probe_relationship_immutable` rejects any `UPDATE` once `status = 'PUBLISHED'`, and rejects
`DELETE` of a `PUBLISHED` row. Published rows are permanent, but **new rows can be published after
a given decision**, which is a second, independent reason (beyond MVCC) that re-deriving "the
working set" from a fresh `resolve()` call at replay time cannot reproduce a historical decision.

`core.assessment_response` (`V006__assessment_responses.sql`): `trg_...` equivalent
(`protect_assessment_response`) rejects `UPDATE`/`DELETE` unconditionally — confirmed immutable.
`core.assessment_attempt_item` (`V045__diagnostic_form_selection.sql`): its own comment states
"Written once at attempt creation and immutable thereafter"; no trigger updates or deletes a row.

## Missing persistence (the actual gap)

Nothing in the current schema records, at decision time:

- which relationship-authorized hypotheses `V6` admitted into its bounded, de-duplicated,
  actionable working set (Amendment 3 §E–§H);
- which candidate probes survived destination-eligibility (exposure) filtering for each of those
  hypotheses;
- whether `V6` activated, and if not, which of the eight `V6FallbackReason` values applied.

`BusinessEventLogger`'s existing telemetry for this (`assessment.probe.selection.v6`) is a log line,
not a queryable, immutable, FK-consistent database record — it cannot serve as replay input.

## Candidate options considered

| Option | Disposition |
|---|---|
| Persist exact V6 decision-time exposure/candidate snapshot (Option A) | **Chosen** — smallest boundary that closes both the MVCC defect and the curriculum-growth defect; see ADR §D–§L |
| Serialize attempt creation per learner (advisory lock or equivalent) (Option B) | Rejected as the primary fix — it is a broader live-runtime/architecture change (throughput, cross-version semantics, deadlock ordering) affecting `V1`–`V5` equally, not a `V6`-scoped replay fix; recorded as a revisit trigger (ADR §DD) rather than adopted here |
| Persist a monotonic attempt/selection ordering token (a sequence) (Option C) | Rejected — PostgreSQL sequence values are allocated at statement-execution time, before commit, identically to `created_at`; a sequence does not encode commit order and reintroduces the exact defect under a different column name |
| Weaken the replay contract to "best-effort" and stop claiming exact replay (Option D) | Rejected as the terminal answer, but is exactly the honest interim disposition for every `V6` attempt created before this amendment's implementation ships (ADR §X) |
| Couple replay to PostgreSQL internal MVCC identifiers (`xmin`/`txid`) | Rejected outright — not durable, not portable, wraps around, and would couple an application-level reproducibility guarantee to a storage-engine internal this codebase relies on nowhere else (ADR §C) |

## Final recommended boundary

Persist the exact `V6`-actionable hypothesis working set and its surviving candidate probes as they
existed at the original destination-attempt decision — full five-field `DiagnosticHypothesis`
identity per hypothesis, `probeItemVersionId` per candidate — scoped to the destination attempt,
written atomically in the same `DiagnosticService.createAttempt` transaction, whenever
`selection_policy_version = DIAGNOSTIC_SELECTION_V6`, regardless of final outcome. See the ADR's
own §G–§L for the full freeze and conceptual schema; no migration is created by this discovery
report or by Amendment 4 itself.
