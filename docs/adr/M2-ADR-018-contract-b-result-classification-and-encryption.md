# M2-ADR-018: Classification, access and encryption at rest for the Contract B result

- **Status:** **Accepted for the RAMALS MVP/research environment — 2026-08-27.** Signed off by
  Sunil Pandey (see [Governance approval](#governance-approval)). **Not approved for
  organisational or production deployment**, which requires reassignment and re-approval under the
  deploying organisation's own governance process.
- **Date:** 2026-08-27 (proposed and approved the same day; the approval is recorded below)
- **Satisfies:** M2-ADR-017 §6 prerequisites 3 and 4, **within the MVP/research scope only**.
- **Relates to:** M2-ADR-017 (which requires this), M2-ADR-016, M1-ADR-005, M2-ADR-012, V021, V023,
  V035, V036
- **Originates here**, on the same basis as M2-ADR-016 and M2-ADR-017.

## Context

M2-ADR-017 decided that Contract B may persist a normalized `diagnostic-proposal.v1` result, that it
is the only table in the RAMALS schema permitted to contain model output, and that it must be
encrypted at rest with a restricted grant set and an audited read path. It then deliberately stopped:
§6 prerequisites 3 and 4 hold `V037` until the classification is signed off and the encryption
mechanism is chosen.

This ADR supplies both, and separates what an engineer may decide from what an accountable human
must. The classification rules, access matrix, encryption architecture, key lifecycle and failure
semantics are engineering decisions and are made here. **A sign-off is not an engineering decision,
and this ADR does not manufacture one.**

Two repository facts constrain the answer and are worth stating before the decision rather than
after:

- **There is no key-management service in this platform today.** Secrets reach the application as
  environment variables sourced from a Kubernetes `Secret` (`ramals-t15-runtime`), read through
  `${...}` placeholders in `application.yml`. Nothing else exists to build on.
- **There is no encryption in any migration today, and no `pgcrypto`.** The existing ledger tables
  need none, because `V023` made redaction structural: they hold no free-text column, so there is
  nothing to encrypt. The Contract B result table is the first table in this schema where that
  argument does not apply.

## Decision

### 1. Data classification

The persisted normalized `diagnostic-proposal.v1` is classified **RESTRICTED — LEARNER-DERIVED
MODEL OUTPUT**.

The class is defined here because the repository has no prior classification vocabulary to inherit.
If the organisation maintains one, this maps into it rather than replacing it, and that mapping is
one of the pending items below.

What the class means, and why this data earns it:

- It is **model output about an identified learner** — a diagnosis of what a person does and does
  not understand. It is derived rather than declared, which makes it *more* sensitive than the
  inputs, not less: the learner never said it, and it may be wrong.
- It is **transient by design**. Unlike the 400-day provenance in `core.ai_execution`, it exists
  only until the gate decision commits, and the class carries that expectation.
- It is **not evidence and not authoritative**. The authoritative artifact is the gate decision.
  The result is the raw material a replacement worker uses to reach one, and nothing downstream
  should ever read it as a verdict.

Prohibited content is unchanged from M2-ADR-017 §2 and restated because a classification without a
content boundary is a label: no chain-of-thought or internal reasoning, no raw provider response,
no prompts or grounded context, no credentials. Enforcement is structural — the plaintext is
validated against `diagnostic-proposal.v1` before encryption, and that schema has no field for
reasoning.

### 2. Accountable owner role

The accountable owner is the **RAMALS Platform Data Owner** — a role, deliberately, not a person.

The role owns the classification, the access matrix, the retention ceiling, and any future change
to what may be stored. It is accountable for the annual re-review of this ADR and for approving any
grant added to the result table.

**The individual filling this role is not named here and cannot be**, which is the first pending
item below.

### 3. Access matrix

| Principal | SELECT | INSERT | UPDATE | DELETE | Notes |
| --- | --- | --- | --- | --- | --- |
| `ramals_core_runtime` | ✅ | ✅ | ❌ | ✅ | The only principal with any grant. Reads to adopt, writes on retrieval, deletes on adoption and purge |
| `ramals_core_migration` | ❌ | ❌ | ❌ | ❌ | DDL only. It creates the table and never reads it |
| `ramals_ai_runtime` | ❌ | ❌ | ❌ | ❌ | The AI plane is stateless (M2-ADR-017 §1) and has no reason to reach this table |
| Reporting / analytics / evaluation | ❌ | ❌ | ❌ | ❌ | **Explicitly prohibited.** They read `core.ai_execution*` provenance, which is what they actually need |
| Human operators | ❌ | ❌ | ❌ | ❌ | No standing access. Break-glass is a separate, audited, time-boxed grant approved by the Data Owner |

**No `UPDATE`, ever.** A stored result is immutable once written. A result that can be rewritten is
not evidence of what the provider returned, and there is no legitimate reason to amend one — the
lifecycle is write, read once, delete.

**The prohibition on analytics is the load-bearing row.** `core.ai_execution` is retained 400 days
and read by evaluation and reporting; that is precisely why the result must not live there and must
not share its grants. A single convenience grant here converts a minutes-long exposure into a
years-long one.

### 4. Tenant and learner isolation

Isolation is by construction, not by filter. Every read is keyed by the durable `request_id`, which
is derived from the workflow run, which is derived from the authenticated OIDC subject. **No
caller-supplied learner identifier is accepted anywhere on this path** — the same property
`DiagnosticAssessmentService` already relies on, which is what makes reading another learner's
result unreachable rather than merely checked.

No query on this table may be keyed by learner id, and no bulk or range read is permitted: the
access pattern is one row by primary key. A reader that can enumerate results can enumerate
learners.

### 5. Audit requirements

**Every read of a stored result is audited**, without exception, recording: the `request_id`, the
workload identity that read it, the timestamp, and the outcome (adopted, not adopted, not found).
A read that is not audited is a defect, not a performance optimisation.

Writes and the adoption-time delete are recorded in the append-only `core.ai_execution_transition`
ledger. The purge sweep records the count and the age distribution of what it removed.

A result that is **read and never adopted** is operationally interesting — it means a worker
retrieved a learner's model output and then did not use it — and must be visible as its own event
rather than inferred from the absence of a decision.

### 6. Provider identifiers, usage and cost metadata

These are **not** RESTRICTED and are handled differently from the result body, deliberately:

| Data | Classification | Where it lives | Encrypted |
| --- | --- | --- | --- |
| `provider_execution_id`, `custom_id`, `provider_message_id`, `provider_request_id` | Internal — bounded identifiers, no learner content | `core.ai_provider_execution`, `core.ai_execution` | No |
| Usage counts, estimated cost, model, route, timestamps | Internal — operational metadata | Alongside the identifiers | No |
| `result_digest` | Internal — SHA-256, not reversible | `core.ai_execution_result` | No |
| **Normalized result body** | **RESTRICTED** | `core.ai_execution_result` only | **Yes** |

Encrypting the identifiers and cost metadata would be actively harmful: they are exactly what the
reconciliation sweep matches on and what the cost-evidence qualification scenario counts, and
neither can operate over ciphertext. They carry no learner content, which is what makes leaving
them readable defensible rather than merely convenient.

### 7. Encryption architecture — application-layer envelope encryption

**Encryption and decryption happen in the Spring application layer.** The database stores ciphertext
and never holds the key.

**PostgreSQL-native `pgcrypto` is rejected**, for four reasons in descending order of weight:

1. **`pgcrypto` puts the key in the SQL statement.** From there it reaches query logs,
   `pg_stat_statements`, and any slow-query capture. A key that travels in-band with the query is a
   key that leaks through observability rather than through an attack.
2. **It leaves the database process able to see plaintext.** Application-layer encryption makes the
   database *structurally* unable to read the result; `pgcrypto` merely arranges that it usually
   does not. This repository has an established preference for the former — `V023`'s
   *"redaction is structural, not procedural"*, enforced by the absence of a column rather than by
   a routine that runs afterwards.
3. **Rotation becomes a data-migration problem** with both keys present in SQL statements, rather
   than a background re-encryption the application performs with the key material it already holds.
4. **It couples the crypto to the DBMS.** The mechanism should survive a database version upgrade or
   a managed-service migration without a re-encryption event.

Volume or storage-level encryption is **not sufficient on its own** and is not accepted as the
control. It protects against a stolen disk and does nothing against an over-broad grant or a
database read — which is the realistic exposure for the one table in this schema holding model
output about a learner. It remains welcome as a second layer.

**Ciphertext column format.** `normalized_result BYTEA NOT NULL`, holding a self-describing envelope
so a stored value can be decrypted without out-of-band knowledge of how it was produced:

```
version (1 byte) | key_id_len (1 byte) | key_id | nonce (12 bytes) | ciphertext+tag
```

AES-256-GCM, with the request identity bound as additional authenticated data so a ciphertext moved
to a different row fails to authenticate rather than decrypting into the wrong learner's record.
`encryption_key_id` is stored as its own column as well as inside the envelope: duplicated on
purpose, because the column is what a rotation sweep queries on and the envelope is what makes a
value self-contained.

### 8. Key lifecycle

**Custody.** A single active data-encryption key, versioned by `encryption_key_id`. Key material is
supplied to the application through the existing secret channel — an environment variable sourced
from a Kubernetes `Secret` — because that is the only mechanism this platform has, and inventing a
second one for this table would be worse than reusing the one every other credential already uses.

**The application must depend on an interface, not on that channel.** `V037` implementation depends
on a narrow `ResultEncryptionKeyProvider` port: *"give me the active key id"*, and *"give me the key
material for this key id"*. The initial adapter reads the environment. **No vendor-specific cloud
KMS is wired**, and none should be until the platform commits to one — but the port is the seam that
makes that a later adapter rather than a rewrite, and it must exist from the first commit.

**Rotation.** Rotation introduces a new key id and marks it active. New results encrypt under the
new key immediately.

**Decrypt-old / encrypt-new.** Existing rows are **not** re-encrypted, and this is a deliberate
simplification the retention ceiling earns: an unadopted result lives at most 30 days, so a rotation
drains naturally within one ceiling period. Decryption therefore accepts any key id still held; the
retired key material must remain available until no row references it, which is queryable, and is
then removed. If a key must be revoked *faster* than the ceiling — a compromise — the correct action
is to purge the affected rows, not to re-encrypt them: an unadopted result is reconstructible by
re-running the diagnostic, and a compromised key is not worth preserving data for.

**Failure to obtain key material is fail-closed.** See §10.

### 9. Retention and purge

**Delete on adoption.** The result row is deleted **in the same transaction** that commits the gate
decision. Not scheduled, not marked — deleted. Once the decision exists, the result is redundant
with an artifact the platform already owns, and the shortest possible exposure is the transaction
boundary.

**30-day hard ceiling for unadopted results**, chosen against the provider's own 29-day retention so
RAMALS never promises recovery from a result the provider has already dropped.

**Purge ownership.** The sweep is owned by the platform runtime and runs as an operator- or
job-invoked function, following `V023`'s precedent: the platform still has no scheduler, and that
migration's judgement stands — *"shipping a function an operator can run, and can be tested, is
honest; shipping a policy with no mechanism at all is a comment pretending to be a control."*

**The result table is deliberately not append-only.** `V021`/`V022`'s triggers reject every `DELETE`,
which `V023` had to reconcile with a 400-day retention floor. Copying that pattern here would make
delete-on-adoption unimplementable. The result table takes an `UPDATE`-rejecting trigger and **no**
`DELETE` restriction — immediate deletion is the policy, not an exception to it.

**Observability.** The purge records rows removed, oldest age at removal, and any row that reached
the ceiling unadopted. A non-zero unadopted count is a reconciliation-health signal, not routine.

### 10. Failure semantics — fail closed, everywhere

| Condition | Behaviour |
| --- | --- |
| Key material unavailable at **write** | **Refuse to store the result.** The execution does not become adopted; it remains recoverable while the provider still holds it. Never store plaintext, and never store unencrypted "temporarily" |
| Key material unavailable at **read** | **Refuse to adopt.** Surface an explicit operator-visible state. Never treat an undecryptable result as absent, which would look like a clean re-runnable request and could resubmit to the provider |
| Ciphertext fails authentication | Treat as corruption. Refuse, alert, do not adopt, do not delete — the row is evidence |
| Schema validation fails before encryption | Refuse to store. The prohibition on reasoning content is enforced here, and a failure means the invariant would have been broken |
| Purge cannot run | Alerts. Results outliving the ceiling is a governance failure, not a backlog |

**Plaintext is never logged**, at any level, including debug and including on the failure paths
above. Errors reference the `request_id` and the key id; they never carry the result, a fragment of
it, or its length. The existing log-redaction test is the precedent, and this extends it rather than
relying on reviewer vigilance.

## Governance approval

Recorded 2026-08-27. This ADR was Proposed with two items marked PENDING HUMAN SIGN-OFF, because
neither is an engineering decision. Both are now signed off, for one scope.

| Item | Assignment |
| --- | --- |
| **Platform Data Owner** | **Sunil Pandey** |
| **Interim Contract-B Key Custodian** | **Sunil Pandey** |
| **Scope** | **RAMALS MVP / research environment only** |
| **Data classification approved** | `RESTRICTED — LEARNER-DERIVED MODEL OUTPUT` |

**What was approved.** The classification in §1, the access matrix in §3 including the prohibition
on reporting, analytics, evaluation and AI-plane access, the tenant and learner isolation rules in
§4, the audit requirements in §5, the application-layer envelope-encryption model in §7, the key
lifecycle in §8, and the retention and purge rules in §9 — as already written, without amendment.

**What this approval is not.** It is **not valid for organisational or production deployment**, and
must not be cited as such. A production deployment requires the deploying organisation to reassign
both roles under its own governance process and to re-approve the classification against its own
scheme. This ADR's mapping onto an organisational classification vocabulary remains open, and is a
revisit trigger rather than a settled question.

**One role, two hats — named rather than glossed over.** The Platform Data Owner and the Key
Custodian are the same individual. In a production deployment that would be a segregation-of-duties
finding: the person who approves what may be stored is also the person who holds the key that
protects it, so there is no independent check on either. In a single-maintainer MVP/research
environment there is no second party to hold the other role, and the alternative — leaving Contract
B blocked indefinitely — buys no real separation. It is accepted here **because the scope is
research and the reassignment requirement above is binding**, not because the concern does not
apply. Any deployment that outgrows the single-maintainer assumption inherits this as an open item.

### Prerequisite status after this approval

| Prerequisite | MVP / research | Organisational / production |
| --- | --- | --- |
| **3 — data-classification sign-off** | ✅ **Satisfied** — owner named, classification approved | ❌ Not satisfied — requires reassignment and re-approval |
| **4 — encryption mechanism and custody** | ✅ **Satisfied** — mechanism decided in §7–§8, custodian named | ❌ Custody not satisfied — requires a production custodian, and should not be the Data Owner |

`V037` is **no longer blocked on governance for the MVP/research environment**. It remains blocked
on the engineering acceptance criteria below, and this ADR still authorizes no migration: approving
the model is not the same as building it.

## Acceptance criteria that unblock V037

`V037` may proceed when all of the following are true. The first two are governance and are now
satisfied for the MVP/research scope; the rest are engineering work this ADR specifies well enough
to do, and none of it has been done.

1. ~~The classification in §1, the owner role in §2 and the access matrix in §3 are **signed off by
   a named individual** in the RAMALS Platform Data Owner role, recorded in this ADR.~~
   ✅ **Satisfied for MVP/research 2026-08-27** — Sunil Pandey, recorded above.
2. ~~The **key custodian is named**, with the rotation and unavailability escalation paths
   recorded.~~ ✅ **Satisfied for MVP/research 2026-08-27** — Sunil Pandey as interim custodian.
   The escalation path is the single maintainer, which is a consequence of the scope and is
   recorded as such rather than presented as an on-call rotation.
3. A `ResultEncryptionKeyProvider` port exists with an environment-backed adapter, and **no
   vendor-specific KMS dependency** is introduced.
4. The envelope format of §7 is implemented with AES-256-GCM and request-identity AAD, and a test
   proves a ciphertext moved between rows **fails to authenticate**.
5. Fail-closed behaviour is proven by test for every row of §10 — in particular that an
   undecryptable result is never treated as absent.
6. `V037` grants exactly the access matrix of §3, with a migration-contract test asserting that no
   reporting, analytics, evaluation or AI-plane role holds any grant on the result table.
7. Delete-on-adoption is proven transactional: a test showing the result row is gone in the same
   transaction that commits the gate decision, and still present if that transaction rolls back.
8. The purge function exists, is invokable, and is tested against the 30-day ceiling.
9. A test proves no plaintext reaches logs on any success or failure path.

## Alternatives rejected

- **`pgcrypto` / PostgreSQL-native encryption.** Rejected in §7. The decisive objection is the key
  travelling in the SQL statement and into query logs; the structural one is that the database
  remains able to read plaintext.
- **Volume encryption as the only control.** Protects a stolen disk, not an over-broad grant. Kept
  as a second layer, refused as the control.
- **A vendor KMS now.** Would resolve custody cleanly and commit the platform to a cloud dependency
  it has not chosen, ahead of a decision nobody has asked for. The port makes it a later adapter.
- **Re-encrypting on rotation.** Correct for long-lived data and unnecessary here: a 30-day ceiling
  drains a rotation on its own, and the complexity would buy nothing.
- **Storing the result unencrypted and relying on the restricted grant set.** The grant set is one
  `GRANT` away from being wrong, and the failure is silent and retroactive.
- **Naming a placeholder approver to mark prerequisite 3 complete.** The most tempting alternative
  and the worst: it would convert an open governance question into an apparently closed one, and the
  next reader would have no way to tell.

## Consequences

- **`V037` remains blocked**, now on two precisely-stated human decisions rather than on an
  unscoped prerequisite. The engineering work is fully specified and can be reviewed in advance.
- **The result table becomes the only encrypted column in the schema**, and the only one whose key
  handling matters. That singularity is deliberate — one governed exception is auditable.
- **Rotation correctness depends on the retention ceiling.** If the 30-day ceiling is ever raised,
  the no-re-encryption simplification must be revisited in the same change.
- **An undecryptable result is an outage, not a degradation.** Fail-closed means a key problem stops
  adoption rather than silently producing re-runs; that is the correct trade and it should be in the
  runbook.
- **This ADR must be re-reviewed annually** by the accountable owner, alongside the classification.

## Revisit triggers

- **RAMALS is deployed beyond the MVP/research environment.** The approval above does not travel
  with it: both roles must be reassigned under the deploying organisation's governance, and the
  Data Owner and Key Custodian should then be different people.
- The organisation adopts a formal data-classification scheme this must map into.
- The platform commits to a key-management service, which replaces the environment-backed adapter.
- The 30-day retention ceiling changes, which invalidates the rotation simplification.
- The prohibited-content list changes — in particular any proposal to persist reasoning content,
  which would reopen the classification rather than adjust it.
- A key compromise, which exercises the purge-rather-than-re-encrypt decision in §8.
