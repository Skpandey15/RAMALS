# RAMALS MVP-0 Design Package — AR-0 Baseline Manifest

**Frozen:** 2026-08-14 · **Purpose:** the single authoritative document set entering the **AR-0 Architecture Review Gate**.

This folder is the curated MVP-0 baseline. Only the latest, canonical version of each document is included. Read in numeric order:

> vision → principles → design → data → tech → security → performance → delivery → execution

Integrity of the frozen set is recorded in `checksums.txt` (SHA-256). Re-verify before AR-0 to confirm nothing changed under review.

---

## Included documents (9)

| # | Document | Ver | Role in the package |
|---|----------|-----|---------------------|
| 01 | Adaptive Personalized Learning Platform Design | v1.2 | Product vision & learning model — the north star |
| 02 | Architecture Design Principles & Patterns | v1.0 | Engineering rulebook: DDD, hexagonal, SOLID, idempotency, anti-patterns |
| 03 | MVP-0 Complete Design Package (HLD/LLD) | v1.2 | Master technical design: HLD + LLD + API + Security + Testing + ADRs |
| 04 | MVP-0 Database Architecture Design | v1.1 | Authoritative data architecture: schema, provenance, concurrency, roles |
| 05 | Technology Stack Architecture | v1.1 | Technology selection & decision framework |
| 06 | MVP-0 Zero Trust Security Architecture | v1.1 | Final authoritative security architecture |
| 07 | MVP-0 Performance, Scalability & Reliability Test Matrix | v1.1 | NFR / performance / scalability / reliability |
| 08 | MVP-0 CI/CD Architecture & Delivery Design | v1.2 | Delivery, quality gates, deployment trust model |
| 09 | MVP-0 Implementation Master Plan | v1.0 | Execution plan + correlation/traceability (interactionId/traceId) contract |

---

## Present in this folder but outside the freeze

| Document | Why it is here, and why it is not frozen |
|----------|------------------------------------------|
| Production Grade Coding & Performance Standards (React/Java/Python) v1.0 | An engineering standards document, not part of the MVP-0 architecture baseline that AR-0 reviews. It sits alongside the nine because that is where it is useful, and it is deliberately absent from `checksums.txt`: the freeze covers the documents under review, and widening it to whatever happens to share the directory would make the hash set mean less, not more. Whether it belongs in the frozen set is a governance decision, not a filing one. |

## Excluded from the freeze (with rationale)

| Document | Location | Why excluded |
|----------|----------|--------------|
| Adaptive Platform Design (unversioned, v1.1) | docv1, docv2 | Superseded by v1.2 |
| CI/CD Design (v1.0, v1.1) | docv1, docv2 | Superseded by v1.2 |
| HLD/LLD Complete Package (v1.0, v1.1) | docv1, docv2 | Superseded by v1.2 |
| Database Architecture v1.0 | docv1 | Superseded by v1.1 |
| Performance Matrix (v1.0) | docv1 | Superseded by v1.1 |
| Technology Stack v1.0 | docv1 | Superseded by v1.1 |
| MVP-0 Adaptive Learning Foundation | docv1 | Superseded precursor; overlaps Platform Design v1.2 + HLD/LLD v1.2 (duplication/drift risk) |
| MVP-1 Implementation Design | docv1 | MVP-1 scope; outside the MVP-0 AR-0 gate — retain as a forward reference only |

---

## Open flags to resolve in AR-0 (seeds for the Step 1 consistency audit)

1. **Resolved — Zero Trust authority.** `RAMALS_MVP0_Zero_Trust_Security_Architecture_v1.1.docx` is the final authoritative security source. HLD/LLD v1.2 §22–§23 provides integrated context but must defer to Zero Trust v1.1 where security requirements differ.

2. **Append-only enforcement.** Database v1.1 grant-enforces `audit.*`, but leaves `core.evidence / core.mastery_snapshot / core.decision_record` on application convention while security asserts immutability. Reconcile via per-table `REVOKE UPDATE, DELETE` or a dedicated ledger schema.

3. **Mastery reproducibility.** Confirm `numeric`-only (no `double precision`) on the mastery path, and a single computation locus, consistently across Platform v1.2, HLD/LLD v1.2 and Database v1.1.

4. **evidence_confidence & thresholds.** Verify Platform v1.2 and HLD/LLD v1.2 carry the exact `0.40 / 0.35 / 0.15 / 0.10` confidence weights and per-skill-version thresholds pinned in Database v1.1 — word-for-word, not paraphrased.

5. **Java ↔ Python boundary.** Confirm HLD/LLD v1.2 specifies the wire pattern (sync/async, transaction ownership, LLM-timeout behavior), not just the principle.

6. **Canonical MVP roadmap.** Database v1.1 claims a canonical MVP-0…5 sequence; verify every document adopts the same numbering.

7. **Correlation contract coverage.** Implementation Master Plan defines interactionId/traceId propagation (§4–§5); confirm HLD/LLD, Database (`decision_record.trace_id`) and CI/CD observability all reference the same contract rather than restating it.

---

*This manifest is the AR-0 "freeze the baseline" artifact. The audit reviews exactly the nine files listed above at the hashes in `checksums.txt`.*
