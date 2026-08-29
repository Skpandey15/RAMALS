# Contract B — accountable-owner approval record

- **Purpose:** the approval artefact for Contract B Definition-of-Done **criterion 9** — *"Security,
  performance and operational runbooks are approved."*
- **Status:** **PENDING SIGN-OFF.** The three artefacts criterion 9 requires exist and are listed
  below. **No approval has been given.** Criterion 9 remains **BLOCKED** until the block at the
  bottom of this document is completed by the accountable owner.
- **Prepared:** 2026-08-29, at `main` `1ffe322edd4d5d75c90d6ff1551df1e4318ddb9e`.

## Why this document exists separately

Criterion 9 is the only Contract B criterion whose content is a **decision** rather than a
mechanism. Every other criterion is satisfied by evidence that a machine produced and a test
asserts. This one is satisfied by a named, accountable human saying yes, having read what they are
saying yes to.

That distinction is the reason this record is separate from the documents it approves, and the
reason it is unsigned. An engineer can write a runbook. An engineer cannot approve it on the owner's
behalf, and a document that recorded an approval nobody gave would be worse than no document — it
would convert a governance gap into a false governance record, which is precisely the failure mode
the Definition of Done's *"not accepted on argument"* rule exists to prevent.

**M2-ADR-018 set the precedent.** It was recorded as *Proposed* with two items marked
`PENDING HUMAN SIGN-OFF`, and was approved later, in the same document, by a named individual for a
named scope. This record follows that shape exactly.

## The accountable owner

Per **M2-ADR-018 §2**, the accountable owner is the **RAMALS Platform Data Owner** — a role, not a
person. Per that ADR's governance approval, the role is currently filled by **Sunil Pandey**, for
the **RAMALS MVP / research environment only**.

That assignment is **not valid for organisational or production deployment**, which requires
reassignment and re-approval under the deploying organisation's own governance process. Any approval
recorded below inherits that scope limitation and cannot be cited beyond it.

## What is being submitted for approval

| Artefact | Location | Covers |
| --- | --- | --- |
| **Operational runbook** | [`docs/architecture/contract-b-operational-runbook.md`](../architecture/contract-b-operational-runbook.md) | Worker operation · provider outage · lost acknowledgement / orphan recovery · `INCONCLUSIVE` · `MULTIPLE` / duplicate · `UNKNOWN_TERMINAL` · encryption-key failure · persistence and adoption failure · purge failure · cost/token anomaly · credential rotation · alert and triage procedure · safe restart and recovery expectations |
| **Security review** | [`mvp2-contract-b-security-review.md`](mvp2-contract-b-security-review.md) | Secret handling · least privilege and DB ACLs · no plaintext or model output in logs · result encryption and key handling · correlation identifiers · provider data exposure · retention and purge · duplicate and adoption fencing · failure-mode safety |
| **Performance characterization** | [`mvp2-contract-b-performance-characterization.md`](mvp2-contract-b-performance-characterization.md) | Cadence · lease and concurrency · enumeration bounds · inspection-budget semantics · durable memo effect · rate-limit exposure · workspace-density assumptions · cost · known limits carried as debt |

All three are written against `main` at the SHA above and cite code, migrations and tests rather
than asserting behaviour.

## What approval would and would not mean

**Would mean:** the owner has read the three artefacts, accepts the residual findings named in them
as appropriate to carry in the MVP/research environment, and accepts operational responsibility for
the procedures the runbook describes.

**Would not mean:**

- **Not route activation.** Criterion 9 is one of nine. Approving these documents does not authorise
  enabling `ramals.contract-b.enabled`, which remains a separate decision.
- **Not production readiness.** The scope limitation above is binding.
- **Not acceptance of the residuals as closed.** They are carried, named, and listed below.

## Residual findings the owner is being asked to accept

Carried forward from the two reviews. None is a defect in what criterion 9 asks for; each is a
bounded item that would otherwise be invisible in an approval.

| # | Finding | Source | Why it is carried |
| --- | --- | --- | --- |
| S1 | Platform Data Owner and Key Custodian are the same individual | Security review §4; M2-ADR-018 governance | A segregation-of-duties finding in any multi-person deployment. Already accepted for research scope with reassignment binding on production. Not re-accepted here — confirmed as still standing. |
| S2 | The submission path treats **any** status the AI plane chooses as proof nothing was created | Security review §9 | Pre-existing, documented in `durable.py`. A 5xx raised after `batches.create` succeeded would record a definite `FAILED` for an execution that exists. Deliberately unchanged in `#187`. **Should be resolved before route activation.** |
| S3 / P5 | No metrics or alerting on the reconciliation worker | Both reviews; closure assessment W4 | Already `ACCEPTED_DEBT` under criterion 6. The runbook's triage section is the manual substitute. |
| P1 | The inspection budget is per process; N workers means ~N× request rate | Performance §9 | M2-ADR-020 §7 states it; deploying more than one worker is a recorded revisit trigger. |
| P2 | Listing cost is not reduced by the memo and grows with workspace activity | Performance §9 | Revisit trigger in M2-ADR-020. |
| P4 | `Retry-After` unverified against a real 429 | Performance §6 | No 429 induced, deliberately. The fallback path is tested. |
| P6 / P7 | No load or soak testing; cross-process concurrency not exercised | Performance §9 | Research-volume characterization only. Belongs with AWS multi-replica qualification. |

## Approval decision

> **PENDING HUMAN SIGN-OFF.**
>
> This block is deliberately incomplete. It must be filled in by the accountable owner, in a commit
> attributable to them, and must not be completed by anyone acting on their behalf.

| Item | Value |
| --- | --- |
| **Platform Data Owner** | *(pending)* |
| **Date of approval** | *(pending)* |
| **Scope approved** | *(pending — expected: RAMALS MVP / research environment only)* |
| **Operational runbook** | ☐ Approved |
| **Security review** | ☐ Approved |
| **Performance characterization** | ☐ Approved |
| **Residual findings S1, S2, S3/P5, P1, P2, P4, P6/P7 accepted as listed** | ☐ Accepted |

**To complete this approval**, the accountable owner replaces the pending values, ticks the boxes
they are approving, and commits the change under their own attribution. Criterion 9 becomes `PASS`
at that commit and not before.

**If the owner does not approve** — in whole or in part — the correct outcome is that criterion 9
stays `BLOCKED` and the specific objection is recorded here. That is a legitimate result, not a
failure of this exercise.

## What must not happen

- **Nobody may complete the block above on the owner's behalf.** An approval recorded without the
  owner's act is a fabricated governance record.
- **Criterion 9 must not be marked `ACCEPTED_DEBT`.** The closure assessment's own rule governs:
  *"ACCEPTED_DEBT is not available for a criterion whose stated requirement is simply absent."* An
  unsigned approval is an absent approval. Debt-accepting an approval is additionally
  self-contradictory, because the entire content of the criterion is that someone accountable said
  yes.
- **The criterion must not be amended to fit what exists.** Criteria 3 and 8 were amended once
  against evidence about the *provider's* capabilities. Amending 9 against evidence about RAMALS'
  own governance would be a different act wearing the same clothes.
