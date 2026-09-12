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

## Why source-attempt identity also fails to be reconstructable (a second, independent defect)

`AssessmentRepository.findMostRecentCompletedAttempt(learnerId, assessmentVersionId)` — the query
that chooses `V6`'s source attempt (Amendment 3 §C) — is:

```sql
... WHERE learner_id = ? AND assessment_version_id = ? AND status = 'COMPLETED'
    ORDER BY created_at DESC, id DESC
    LIMIT 1
```

This is a **"most recent as of when the query runs"** lookup, not a fact fixed once at decision
time. Re-running it later, after the same learner has completed a further attempt under the same
assessment version, returns that later attempt instead — a different, and wrong, answer for a
historical replay. This defect is **independent of the §B MVCC/concurrency defect**: it requires no
concurrent transaction at all, only ordinary sequential learner activity between the original
decision and a later replay. It was found during review of an earlier Amendment 4 draft, which had
incorrectly classified "the source attempt's own id" as reconstructable (on the unrelated, true fact
that `core.assessment_attempt` rows are never deleted) without checking whether the id could be
*rediscovered* — it cannot. See the ADR's own §F "Source attempt identity" for the frozen rule this
finding produced, and §EE's review-round-corrections entry for the exact wording fixed.

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

- **which source attempt `V6` actually used** — `sourceAttemptId` itself, including the
  `NO_SOURCE_ATTEMPT` case, where the fact "no eligible source attempt existed at decision time" is
  itself the thing that must survive, not be silently re-derived later;
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

Persist, on one header row per `V6`-policy attempt: **which source attempt `V6` used**
(`sourceAttemptId`, authoritative decision-time provenance, `NULL` iff `NO_SOURCE_ATTEMPT`, never
rediscovered by re-running `findMostRecentCompletedAttempt(...)` at replay time) together with the
exact `V6`-actionable hypothesis working set and its surviving candidate probes as they existed at
the original destination-attempt decision — full five-field `DiagnosticHypothesis` identity per
hypothesis, `probeItemVersionId` per candidate — scoped to the destination attempt, written
atomically in the same `DiagnosticService.createAttempt` transaction, whenever
`selection_policy_version = DIAGNOSTIC_SELECTION_V6`, regardless of final outcome. See the ADR's
own §F–§L for the full freeze and conceptual schema; no migration is created by this discovery
report or by Amendment 4 itself.

## Implementation status (M2-ADR-034 Step 4, 2026-09-12)

The boundary this report recommends is now implemented, on branch
`feat/m2-adr-034-step-4-v6-exact-replay-provenance`. Recorded here as a status note, appended after
the fact — it does not revise the discovery or recommendation above.

- **Migration**: `V062__diagnostic_selection_v6_replay_input.sql` adds
  `core.diagnostic_selection_replay_input` (header, one row per `DIAGNOSTIC_SELECTION_V6` attempt,
  unconditional) and `core.diagnostic_selection_replay_candidate_probe` (full five-field
  `DiagnosticHypothesis` identity per surviving candidate), both immutable
  (`trg_diagnostic_selection_replay_input_guard` / `trg_diagnostic_selection_replay_candidate_probe_guard`,
  the same discipline as `trg_probe_provenance_guard`) and insertable only for an `IN_PROGRESS`
  destination attempt.
- **Persistence**: `DiagnosticSelectionReplayInputRepository` is the sole writer, called from
  `DiagnosticService.selectDiagnosticSelectionV6Form` in the same transaction `createAttempt` already
  runs, immediately after `HypothesisDiscriminationDiagnosticSelector.select` returns — before any
  probe-provenance or packet-persistence step, so a write failure here fails attempt creation whole.
- **One authoritative computation**: `HypothesisDiscriminationDiagnosticSelector`'s activation/
  fallback/ranking logic was extracted into a single private `decide(...)` method, called by both the
  live `select(...)` and the new `decideFromPersistedWorkingSet(...)` (used by replay) — there is no
  second, independently written copy of the activation rules for replay to drift from. A dedicated
  `noSourceAttemptDecision()` factory reproduces `NO_SOURCE_ATTEMPT` directly, since an empty
  persisted working set is otherwise ambiguous with a source attempt that authorized nothing.
- **Frozen identifier**: `DiagnosticSelectionReplayInputRepository.SNAPSHOT_CONTRACT_VERSION =
  "DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1"` — independent of `DIAGNOSTIC_SELECTION_V6` (the
  selection policy, unchanged by this step) and of either frozen engine's own version string. Added
  to `EngineVersionFreezeTests` as its own vector/hash; the existing `DIAGNOSTIC_SELECTION_V6` vector
  and hash are untouched.
- **Replay service**: `DiagnosticSelectionV6ReplayService.replay(destinationAttemptId)` loads the
  snapshot (or reports `NOT_AVAILABLE` — deliberately indistinguishable between "pre-Amendment-4"
  and "not a V6 attempt", never inferred from `created_at`), reconstructs the working set, calls
  `decideFromPersistedWorkingSet` (or `noSourceAttemptDecision` when the persisted source attempt is
  `NULL`), and compares the recomputed outcome against the persisted `activated`/`fallback_reason`
  and, when a probe was selected, against `core.diagnostic_probe_provenance` — surfacing any
  divergence as `INTEGRITY_FAILURE` rather than trusting persisted metadata.
- **Replay-availability boundary**: exact replay is available only for attempts created after this
  migration. A pre-existing `DIAGNOSTIC_SELECTION_V6` attempt has no replay-input row and returns
  `NOT_AVAILABLE` — this is never backfilled or simulated from `created_at`, UUID ordering, or
  current state.
- **Superseded helper removed**: `AssessmentRepository.findLearnerExposedLogicalItemIdsBefore` (the
  `created_at`-bounded query this report's own §"Why `created_at` fails" section falsifies) has been
  deleted, along with its two now-superseded tests. The MVCC-race lesson those tests recorded is
  preserved by `DiagnosticSelectionReplayInputPersistenceIntegrationTests
  #persistedSnapshotIsImmuneToTheConcurrentUncommittedAttemptRace`, which proves the persisted
  snapshot (not a `created_at` query) is unaffected by the identical concurrent-uncommitted-attempt
  interleaving.
- **Live `DIAGNOSTIC_SELECTION_V6` behavior is unchanged**: no live selection semantics, activation
  condition, fallback reason, ranking rule, or frozen identifier from Amendment 3 was modified by
  this step; `EngineVersionFreezeTests`' existing `DIAGNOSTIC_SELECTION_V6` hash is byte-identical to
  before this step.

### Correction round (PR #279 review, 2026-09-12)

Review of the Step 4 implementation above (same PR, not a new one) found three replay-integrity
gaps the original implementation left open, all now closed:

- **Snapshot-contract-version validation**: replay now rejects (`UNSUPPORTED_SNAPSHOT_VERSION`) any
  snapshot whose `snapshot_contract_version` is not `DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1`, rather
  than silently interpreting an unrecognized future contract under `_V1` semantics. `V062`'s own
  `CHECK` constraint is frozen to that exact value (widened, never relaxed, by whichever migration
  introduces a `_V2` contract).
- **Selected-probe verification for the `V5`-fallback path**: replay now reads back
  `core.diagnostic_probe_provenance` for both an activated `V6` decision and a `V6`-fallback
  decision (Amendment 4 §S), rather than only the activated case, and checks internal consistency
  against the recomputed decision's own `sourceAttemptId`/`actionableHypothesisCount` without ever
  re-running `V5`'s own probe discovery.
- **Destination-attempt policy validation**: replay now verifies the destination attempt's own
  `selection_policy` is `DIAGNOSTIC_SELECTION_V6` before interpreting any snapshot (Amendment 4 §O's
  own algorithm ordering), and `trg_diagnostic_selection_replay_input_guard` now enforces the same
  fact at the database level -- a replay snapshot can no longer be inserted against a `V1`-`V5`
  attempt.

Additionally hardened: every persisted audit count/status (`candidate_probe_count`,
`actionable_hypothesis_count`, `participating_hypothesis_count`, `step1_status`, `step2_status`) is
now compared against the recomputed decision, not only `activated`/`fallback_reason`; and the
historical winner's `targetSkillCode` is resolved via a narrow, item-version-scoped lookup
(`AssessmentRepository.findAdaptiveEligibleItemsForItemVersions`) rather than the destination
version's whole current item roster.

### Second correction round (PR #279 review, 2026-09-12): `V6` → `V5` fallback provenance

Review found that the first correction round's fallback-path verification checked
`core.diagnostic_probe_provenance` only negatively (absent when no probe *could* exist), never
positively when `V5` genuinely had a working candidate set to choose from. Fixing this required
reading the live `V5` path (`DiagnosticService.resolveHypothesisProbeSelection` and
`HypothesisDrivenProbeDiagnosticSelector.adjustForHypothesisProbe`) rather than hard-coding a rule,
which surfaced two real, code-level subtleties the naive "provenance must always exist and must
always match a persisted candidate" rule would have false-flagged as corruption:

- **Provenance may legitimately be absent even with actionable candidates.** `adjustForHypothesisProbe`'s
  own doc records that V3's mastery-band packet-composition cap can exclude `V5`'s chosen candidate
  from the assembled packet entirely, and `DiagnosticService` only writes provenance when the chosen
  item actually lands in that packet. Whether that exclusion applied is a fact about historical
  mastery/evidence state the Amendment 4 snapshot deliberately never persists -- so replay never
  requires provenance to exist for a fallback decision, only verifies it when present.
- **A present provenance row may legitimately fall outside the persisted candidate set.** `V5`'s own
  resolution walks the identical (miss, relationship-type) enumeration `V6`'s own working-set walk
  does, so whenever that walk completes without hitting `MAX_AUTHORIZED_HYPOTHESES_V6` (never capped
  early), any candidate `V5` could choose was already evaluated -- and, if destination-eligible,
  already admitted -- by `V6` too, and a match is required. Only when `V6`'s own walk stopped early
  at the cap can `V5`'s independent, uncapped walk legitimately reach a candidate `V6`'s own snapshot
  never recorded; that specific divergence is accepted, not flagged, since re-deriving it would mean
  re-running `V5`'s own discovery.

`provenance.source_attempt_id` matching the persisted snapshot's own `sourceAttemptId`, and
`provenance.attempt_id` matching the destination attempt, remain unconditional checks regardless of
the cap. The `V6`-activated path is unchanged in requirement (provenance must always exist and match)
but now compares the full historical identity -- trigger item/objective, relationship type, target
objective, authorizing relationship, and source attempt -- not `itemVersionId` alone.
