# Contract B — accountable-owner approval record

- **Purpose:** the approval artefact for Contract B Definition-of-Done **criterion 9** — *"Security,
  performance and operational runbooks are approved."*
- **Status:** **APPROVED — 2026-08-29**, by the Platform Data Owner, for the RAMALS MVP / research
  environment only. Criterion 9 is satisfied. See [Approval decision](#approval-decision).
- **Binding condition attached to this approval:** residual **S2 must be resolved and separately
  reviewed before `ramals.contract-b.enabled` may be activated in any environment.** This approval
  does not authorize Contract-B route activation or production deployment.
- **Prepared:** 2026-08-29, at `main` `1ffe322edd4d5d75c90d6ff1551df1e4318ddb9e`.

## Why this document exists separately

Criterion 9 is the only Contract B criterion whose content is a **decision** rather than a
mechanism. Every other criterion is satisfied by evidence that a machine produced and a test
asserts. This one is satisfied by a named, accountable human saying yes, having read what they are
saying yes to.

That distinction is the reason this record is separate from the documents it approves. An engineer
can write a runbook; an engineer cannot approve it on the owner's behalf, and a document recording an
approval nobody gave would be worse than no document — it would convert a governance gap into a false
governance record, which is precisely the failure mode the Definition of Done's *"not accepted on
argument"* rule exists to prevent.

**So this record was prepared unsigned and signed separately.** M2-ADR-018 set that precedent: it was
recorded as *Proposed* with two items marked `PENDING HUMAN SIGN-OFF`, and approved later, in the
same document, by a named individual for a named scope. The [approval decision](#approval-decision)
below was given by the owner after the artefacts existed to be read.

## The accountable owner

Per **M2-ADR-018 §2**, the accountable owner is the **RAMALS Platform Data Owner** — a role, not a
person. Per that ADR's governance approval, the role is currently filled by **Sunil Pandey**, for
the **RAMALS MVP / research environment only**.

That assignment is **not valid for organisational or production deployment**, which requires
reassignment and re-approval under the deploying organisation's own governance process. Any approval
recorded below inherits that scope limitation and cannot be cited beyond it.

## What was approved

| Artefact | Location | Covers |
| --- | --- | --- |
| **Operational runbook** | [`docs/architecture/contract-b-operational-runbook.md`](../architecture/contract-b-operational-runbook.md) | Worker operation · provider outage · lost acknowledgement / orphan recovery · `INCONCLUSIVE` · `MULTIPLE` / duplicate · `UNKNOWN_TERMINAL` · encryption-key failure · persistence and adoption failure · purge failure · cost/token anomaly · credential rotation · alert and triage procedure · safe restart and recovery expectations |
| **Security review** | [`mvp2-contract-b-security-review.md`](mvp2-contract-b-security-review.md) | Secret handling · least privilege and DB ACLs · no plaintext or model output in logs · result encryption and key handling · correlation identifiers · provider data exposure · retention and purge · duplicate and adoption fencing · failure-mode safety |
| **Performance characterization** | [`mvp2-contract-b-performance-characterization.md`](mvp2-contract-b-performance-characterization.md) | Cadence · lease and concurrency · enumeration bounds · inspection-budget semantics · durable memo effect · rate-limit exposure · workspace-density assumptions · cost · known limits carried as debt |

All three are written against `main` at the SHA above and cite code, migrations and tests rather
than asserting behaviour.

## What the approval means

**It means:** the owner has read the three artefacts, accepts the residual findings named below as
appropriate to carry in the MVP/research environment, and accepts operational responsibility for the
procedures the runbook describes.

**It does not mean:**

- **Not route activation.** Criterion 9 is one of nine. Approving these documents does not authorise
  enabling `ramals.contract-b.enabled` — and the binding condition below forbids it outright until
  S2 is resolved and separately reviewed.
- **Not production readiness.** The scope limitation above is binding.
- **Not acceptance of the residuals as closed.** They are carried, named, and listed below.

## Residual findings accepted

Carried forward from the two reviews and **accepted as documented** by the approval below. None is a
defect in what criterion 9 asks for; each is a bounded item that would otherwise be invisible in an
approval. Accepting them records them as carried, not as resolved.

| # | Finding | Source | Why it is carried |
| --- | --- | --- | --- |
| S1 | Platform Data Owner and Key Custodian are the same individual | Security review §4; M2-ADR-018 governance | A segregation-of-duties finding in any multi-person deployment. Already accepted for research scope with reassignment binding on production. Not re-accepted here — confirmed as still standing. |
| S2 | The submission path treats **any** status the AI plane chooses as proof nothing was created | Security review §9 | Pre-existing, documented in `durable.py`. A 5xx raised after `batches.create` succeeded would record a definite `FAILED` for an execution that exists. Deliberately unchanged in `#187`. **Accepted subject to the binding condition below: it must be resolved and separately reviewed before route activation.** |
| S3 / P5 | No metrics or alerting on the reconciliation worker | Both reviews; closure assessment W4 | Already `ACCEPTED_DEBT` under criterion 6. The runbook's triage section is the manual substitute. |
| P1 | The inspection budget is per process; N workers means ~N× request rate | Performance §9 | M2-ADR-020 §7 states it; deploying more than one worker is a recorded revisit trigger. |
| P2 | Listing cost is not reduced by the memo and grows with workspace activity | Performance §9 | Revisit trigger in M2-ADR-020. |
| P4 | `Retry-After` unverified against a real 429 | Performance §6 | No 429 induced, deliberately. The fallback path is tested. |
| P6 / P7 | No load or soak testing; cross-process concurrency not exercised | Performance §9 | Research-volume characterization only. Belongs with AWS multi-replica qualification. |

## Approval decision

**APPROVED.** Given by the Platform Data Owner on 2026-08-29, in their own words, and transcribed
here without alteration. The decision is the owner's; this document records it.

| Item | Value |
| --- | --- |
| **Platform Data Owner** | **Sunil Pandey** |
| **Date of approval** | **2026-08-29** |
| **Scope approved** | **RAMALS MVP / research environment only** |
| **Operational runbook** | ☑ **Approved** |
| **Security review** | ☑ **Approved** |
| **Performance characterization** | ☑ **Approved** |
| **Residual findings S1, S2, S3/P5, P1, P2, P4, P6/P7** | ☑ **Accepted as explicitly documented** |

### Binding approval condition

> **S2 must be resolved and separately reviewed before `ramals.contract-b.enabled` may be activated
> in any environment. This approval does not authorize Contract-B route activation or production
> deployment.**

This condition is part of the approval, not commentary on it. S2 is the finding that the submission
path treats *any* HTTP status the AI plane chooses as proof that nothing was created — correct for a
4xx, and wrong for a 5xx raised after `batches.create` has already succeeded, where it would record a
definite `FAILED` for a provider execution that exists.

The condition is proportionate to what activating the route changes. While the route is off, every
Contract B submission is made by a qualification harness under supervision, and a misclassified
submission is observed by whoever is running it. With the route on, submissions arrive from learner
traffic unattended, and a misclassification becomes a durable wrong answer about a learner's work
that nobody is watching for. S2 is therefore latent today and load-bearing the moment the route
opens.

**Consequences of the condition:**

- `ramals.contract-b.enabled` stays `false` in every environment until S2 is resolved **and**
  separately reviewed. Resolving it is not sufficient on its own; the review is part of the
  condition.
- That review is a new artefact, not an amendment to this one. This approval does not extend to it
  and must not be cited as covering it.
- Criterion 9 is satisfied by this approval. Route activation was never something criterion 9
  authorized, and the Definition of Done permits activation only after qualification — so this
  condition narrows an authority that criterion 9 did not grant, rather than qualifying the
  criterion itself.

### What this approval covers, precisely

The three artefacts as written at `1ffe322edd4d5d75c90d6ff1551df1e4318ddb9e`, and the seven residual
findings as documented in the table above — accepted as bounded items appropriate to carry in the
MVP/research environment, not as items that have been resolved.

### What it does not cover

- **Not route activation** — see the binding condition.
- **Not production or organisational deployment.** The scope limitation inherited from M2-ADR-018 is
  binding: production requires reassignment of both the Data Owner and Key Custodian roles under the
  deploying organisation's own governance process, and re-approval against its own scheme.
- **Not the other eight DoD criteria**, which stand or fall on their own evidence.
- **Not W4, P5, AWS work, or any other deferred item.** Accepting a residual as documented debt is
  not scheduling it.

### Re-review

This approval is scoped to the artefacts at the SHA named above. A material change to the Contract B
path — the lifecycle, the persistence model, the access matrix, the retention rules, or the
reconciliation mechanism — invalidates it and requires re-approval, on the same basis M2-ADR-018
requires annual re-review of the classification.

## What must not happen

- **The approval above must not be extended by reading.** It covers three named artefacts at one
  named SHA for one named scope, with one binding condition. It is not a general authorisation for
  Contract B, and citing it as one would be the same error as recording it unsigned would have been.
- **`ramals.contract-b.enabled` must not be activated** until S2 is resolved and separately
  reviewed. This is the binding condition, not a recommendation.
- **Criterion 9 must not be re-opened by a change to the artefacts.** A material change to the
  Contract B path invalidates this approval and requires a new one, recorded here in the same form —
  it does not silently carry forward.
- **The criterion must not be amended to fit what exists.** Criteria 3 and 8 were amended once
  against evidence about the *provider's* capabilities. Amending 9 against evidence about RAMALS'
  own governance would be a different act wearing the same clothes.
