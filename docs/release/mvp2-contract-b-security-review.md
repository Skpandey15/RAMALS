# Contract B — security review

- **Reviewed:** 2026-08-29, at `main` `1ffe322edd4d5d75c90d6ff1551df1e4318ddb9e` (after `#188`).
- **Scope:** the Contract B durable execution path only — `execution/contractb`,
  `execution/crypto`, `V037`–`V040`, and the AI plane's durable surface. Contract A is out of scope
  and unchanged.
- **Method:** each claim below is checked against code, migration or test, and cited. Claims that
  could not be checked are marked as such rather than asserted.
- **Satisfies:** the security half of Contract B Definition-of-Done criterion 9.
- **Approval:** see [approval record](mvp2-contract-b-approval.md). This document is a review, not
  an approval; it does not approve itself.

## Summary

| Area | Finding |
| --- | --- |
| Secret handling | **PASS** |
| Least privilege / DB ACLs | **PASS** |
| No plaintext or model output in logs | **PASS** |
| Result encryption and key handling | **PASS**, with one named residual |
| Correlation identifiers | **PASS** |
| Provider data exposure | **PASS**, with one accepted exposure |
| Retention / purge behaviour | **PASS** |
| Duplicate / adoption fencing | **PASS** |
| Failure-mode safety | **PASS** |

**No finding blocks criterion 9.** Three residuals are named and carried, and each is either
inherent to the provider contract or already recorded as debt elsewhere. None is a defect in this
path's implementation.

---

## 1. Secret handling

**Provider credential is held only by the AI plane.** The platform never sees it: Contract B's
transport authenticates with a workload token, and the Anthropic key is read by
`AnthropicBatchesProvider` from `RAMALS_AI_PROVIDER_API_KEY`. It appears in no RAMALS table, no
ledger row, and no evidence document.

**Settings refuse the unsafe combination.** `durable_execution_enabled` without `provider_api_key`
raises at startup rather than degrading — a durable route with no credential fails loudly instead of
silently falling back, consistent with M2-ADR-016 §4.

**The key is `repr=False`** on the settings model, so it does not appear in a settings dump or a
Pydantic validation error.

**Qualification handled it correctly.** Both real-provider runs verified the credential with a
zero-token `models.list` call, injected it into the process environment, and never displayed or
persisted it. The evidence documents hold batch ids, correlation keys, timestamps and token counts
only.

> **Residual (environmental, not code).** During qualification a credential belonging to the
> maintainer's own environment was rendered into a session transcript by an assertion failure in an
> unrelated test. No repository artefact contains it. Recorded here because a security review that
> omitted it would be incomplete; rotation is the operator's action, not the platform's.

## 2. Least privilege / DB ACLs

`V002` grants the runtime role every privilege on every future `core` table, so a narrow matrix must
be **produced by revoking first**. Every Contract B migration does exactly that, and the resulting
matrix is asserted by test rather than assumed — `ContractBPersistenceIntegrationTests` 6a–6j read
`pg_class.relacl` via `aclexplode` (not `information_schema`, which is filtered to the querying
role and would make emptiness assertions vacuous).

| Table | `ramals_core_runtime` | Rationale |
| --- | --- | --- |
| `ai_provider_execution` | SELECT, INSERT, UPDATE | State advances; rows are never deleted |
| `ai_execution_result` | SELECT, INSERT, DELETE | **No UPDATE** — a sealed result is never rewritten |
| `ai_execution_transition` | SELECT, INSERT | Append-only ledger, also trigger-enforced |
| `ai_reconciliation_work` | SELECT, INSERT, UPDATE, DELETE | A queue |
| `ai_provider_execution_observation` | SELECT, INSERT | Evidence, append-only, also trigger-enforced |
| `ai_enumeration_no_match` | SELECT, INSERT | Disposable optimisation; removal via FK cascade only |

**The AI plane holds nothing.** `ramals_ai_runtime` is revoked from every Contract B table,
conditionally where the role exists. This is enforced from both sides: the plane has no database
driver at all, asserted by `test_no_database_access.py`.

**Functions are not public.** `adopt_ai_execution_result` and
`purge_expired_ai_execution_results` are revoked from `PUBLIC` and granted only to the runtime.

**Checked:** an analytics-shaped role reaches neither the result table nor the purge (test 6e).

## 3. No plaintext or model output in logs

**Structural, not procedural.** Three mechanisms, in order of strength:

1. **The database cannot hold plaintext.** `ck_ai_execution_result_envelope` requires byte 0 to be
   the envelope version, a key-id length of 1–64, and a minimum length of 31. A plaintext write is
   refused by PostgreSQL — there is no configuration or code path in which it succeeds.
2. **Non-text blocks never leave the adapter.** `_text_of` concatenates text blocks and drops every
   other type. Thinking blocks in particular are never read, so internal reasoning cannot reach a
   durable result (M2-ADR-017's prohibition, honoured where it is cheapest).
3. **Provider error bodies are discarded.** `_normalize` keeps the exception class name and the
   `Retry-After` seconds; the provider's message is dropped, because provider errors routinely echo
   the request — which here is a minimized learner context.

**Tested by canary.** `ContractBResultLogSafetyTests` and `ResultEnvelopeCodecTests` attach a
capture to the **ROOT** logger at TRACE — deliberately wide, so a careless statement added anywhere
fails them — and assert a canary value never appears, including in argument arrays and throwable
messages. `ContractBPersistenceIntegrationTests` asserts the canary is absent from all four Contract
B tables after crashes on both sides of the write.

**Observations carry no model output by construction**: identifiers, an outcome enum, token counts
and timestamps only. `V023`'s structural-redaction guarantee extends to them unchanged.

## 4. Result encryption and key handling

**Application-layer envelope encryption** (M2-ADR-018 §7):
`version | key_id_len | key_id | nonce(12) | ciphertext+tag`. The key id travels with the ciphertext,
so a rotation does not orphan existing rows.

**Validation precedes encryption**, and the stored document is **re-serialised from the parsed
`DiagnosticAssessmentProposal`** — so out-of-contract fields have nowhere to go even if a model
returns them. A non-conforming document is refused (`RESULT_SCHEMA_INVALID`), not stored.

**A missing key does not fail the execution.** `ResultEncryptionKeyUnavailableException` leaves the
execution recoverable while the provider still holds the result. The alternative — failing — would
convert a configuration problem into permanent data loss.

**Custody.** The Interim Contract-B Key Custodian is the same individual as the Platform Data Owner
(M2-ADR-018 governance approval).

> **Residual (already recorded, not new).** That is a segregation-of-duties finding in any
> multi-person deployment: the person approving what may be stored also holds the key protecting it.
> M2-ADR-018 accepts it explicitly for the single-maintainer research scope and makes reassignment
> binding on production deployment. This review does not re-accept it; it confirms the finding still
> stands and is still correctly scoped.

## 5. Correlation identifiers

**No learner-identifying data in correlation.** `interaction_id` and `trace_id` are opaque
identifiers — a canonical lowercase UUIDv7 and a W3C trace id. `custom_id` is the server-derived
idempotency key, never a caller-supplied value, which matters for two reasons: correlation cannot be
influenced by a caller, and the key carries no learner attributes.

**Correlation is now persisted per execution** (`V040`) so an execution stays traceable to the
request that created it. Values are blank-mapped to null at the single writer; a blank correlation is
never stored.

**No leakage between scheduled jobs.** The worker establishes a scope per execution and
`CorrelationContext.Scope.close()` restores the prior MDC, asserted by
`ContractBReconciliationCorrelationTests` — including that one execution's correlation never reaches
the next and that the thread is left as found. Scheduler threads are pooled, so this is a real
exposure and not a theoretical one.

**The transport never fabricates an identifier.** Where no correlation exists it omits the header;
the AI plane then generates one. Verified at a real HTTP boundary
(`ContractBHttpBoundaryTests`) and against the live plane during qualification (32/32 canonical, 0
empty).

## 6. Provider data exposure

**What leaves the platform:** the minimized learner context in the prompt, the model name, a token
ceiling, and the `custom_id`. Nothing else — the durable request carries no learner identifier, no
tenant identifier and no free text beyond the prompt itself.

**What the provider retains:** batch results for a documented 29 days. This is the accepted exposure
and it is inherent to using a batch API: RAMALS' own 30-day ceiling was chosen *against* this number
(M2-ADR-018 §9), so RAMALS never holds a result longer than the provider does.

**Enumeration reads workspace-wide.** Recovery lists batches across the workspace and opens
candidates' results to correlate them. Two consequences, both accepted: RAMALS reads batches
belonging to other requests (it must, since the listing carries no `custom_id`), and it records
**nothing** about them beyond "this batch is not this request's" in the disposable memo. No other
request's content, usage or identity is retained.

**Cancellation is implemented but unreachable** — no lifecycle path calls it, so no behaviour depends
on a capability that was never qualified.

## 7. Retention / purge behaviour

**Ceiling is structural.** `ck_ai_execution_result_ceiling` refuses a `purge_after` more than 30 days
after `stored_at`, so retention cannot be extended by writing a row.

**Delete-on-adoption.** The result is deleted in the same transaction that records the decision
(`core.adopt_ai_execution_result`), so a successfully adopted result does not linger to its ceiling.
The ceiling covers what is never adopted.

**A purge that cannot run alerts and rethrows.** Swallowing it would turn a retention breach into
silence, which M2-ADR-018 §10 forbids in terms. Proven by test, including a negative control that a
failing sweep is not silently absorbed.

**Observations are deliberately never purged.** They hold bounded metadata and no model output, and
they are what explains a duplicate after the results are gone.

## 8. Duplicate / adoption fencing

**At most one adopted execution, structurally.** `ai_provider_execution` is keyed on `request_id`
with a unique index on `provider_execution_id`: one request cannot hold two executions, and one
execution cannot be claimed by two requests.

**Fencing.** `submit_fence` is a monotonically increasing CAS token. `recordSubmission` and
`adoptRecoveredIdentity` both require the caller's fence *and* `provider_execution_id IS NULL`, so a
superseded worker cannot write its identity over the worker that replaced it — it defers, recording
`SUBMIT_FENCE_LOST`.

**Adoption is atomic and idempotent.** Decision and result deletion share one transaction; a crash
inside it rolls back both (crash qualification K10).

**A duplicate is never resolved by choosing.** Every discovered execution is recorded with usage;
none is adopted; the execution requires an operator. Proven against the real provider (W2 P4: two
real batches under one `custom_id`, both recorded, neither adopted).

## 9. Failure-mode safety

Every failure mode was checked for whether it fails **closed**:

| Failure | Behaviour | Safe? |
| --- | --- | --- |
| Ambiguous submission | `UNKNOWN_TERMINAL`, never `FAILED`, never resubmitted | ✅ |
| Unclassified exception after submit began | `UNKNOWN_TERMINAL` — cannot prove nothing was created | ✅ |
| Classified refusal | `FAILED` — the only path to it | ✅ |
| Provider unreachable | Non-terminal, retried with capped backoff | ✅ |
| Rate limited | Distinct from an outage; pass stops; `Retry-After` honoured | ✅ |
| Uninspectable candidate | `INCONCLUSIVE`, never `ZERO` | ✅ |
| Bound or budget reached | `INCONCLUSIVE`, never `ZERO` or `ONE` | ✅ |
| Search outcome unknown to this build | Read as `INCONCLUSIVE`, never as absence | ✅ |
| Two or more matches | Refuses adoption, records both | ✅ |
| Key unavailable | Stays recoverable; no plaintext; no failure | ✅ |
| Schema-invalid result | Refused before encryption; `FAILED` | ✅ |
| Purge failure | Alerts and rethrows | ✅ |
| Adapter cannot honour Contract B | Refuses; never degrades to Contract A | ✅ |

**The consistent direction is: refuse rather than guess.** The one place that could have gone the
other way — treating an unclassified exception as a definite refusal, which would license a
resubmission — was found in review of `#181` and corrected before merge.

> **Residual — the submission path's status classification.** `RamalsAiDurableExecutionClient`
> treats *any* HTTP status the AI plane chooses as proof nothing was created. For a 4xx that is
> right. For a 5xx raised *after* `batches.create` succeeded it would record a definite `FAILED` for
> an execution that exists. This is pre-existing, documented in `durable.py`, and **not** a
> Contract B regression; it was deliberately left unchanged in `#187` because altering submission
> classification under a defect fix is how duplicates get created. It is carried as debt and should
> be resolved before the public route is activated.

## Findings carried as debt

| # | Finding | Where recorded | Blocks criterion 9? |
| --- | --- | --- | --- |
| S1 | Data Owner and Key Custodian are one person | M2-ADR-018 governance approval | No — scoped and binding on production |
| S2 | Submission classifies any chosen status as "nothing created" | `durable.py`, this review | No — pre-existing; resolve before route activation |
| S3 | No metrics or alerting on the worker | Closure assessment (W4) | No — already ACCEPTED_DEBT under criterion 6 |

None of these is a new defect, and none is a shortfall in what criterion 9 asks for.
