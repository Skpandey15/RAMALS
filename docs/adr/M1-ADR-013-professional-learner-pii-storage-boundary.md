# M1-ADR-013: Professional learner contact PII stays outside core.learner

- **Status:** Accepted
- **Date:** 2026-08-30
- **Relates to:** ADR 0001, M1-PROF-01, Zero Trust data-minimization baseline
- **Required before:** M1-PROF-01 PR-A

## Context

The existing `core.learner` model intentionally contains no PII. That property is documented in schema comments and is part of the platform's Zero Trust/data-minimization baseline. Professional self-registration introduces names, email, mobile, country/city, verification timestamps and consent evidence. Putting those fields directly on `core.learner` would silently reverse an established security invariant and broaden access to PII across every component that can read the core learner table.

## Decision

1. `core.learner` remains PII-free.
2. First/last name, email, mobile, country/city and contact-verification metadata are stored only in a separate registration/contact PII boundary keyed to the learner identity. The physical schema/table name is selected according to repository conventions during PR-A; an `identity.learner_contact`-style boundary is the intended shape.
3. Professional attributes such as current role, experience, expertise and declared skill level remain in the professional-profile boundary, not `core.learner`.
4. The PII boundary receives least-privilege database/application access. `ramals-ai` has no access to it and still has no direct authoritative PostgreSQL access.
5. PII is not duplicated into unrelated audit/event/metric/trace records. Security/audit events use stable non-PII references and only immutable consent artifact identifiers where needed.
6. `core.learner` continues to use opaque OIDC-sub linkage per ADR 0001.
7. Verified-mobile uniqueness/reservation is enforced within the approved PII/ownership model and remains effective after disable/soft-delete; an ordinary soft-delete predicate must not free a number for reuse.

## Alternatives considered

**Add registration columns to core.learner.** Rejected because it reverses an explicit no-PII security baseline and increases the blast radius of every reader of the core table.

**Store all PII only in Keycloak.** Rejected for MVP-1 because RAMALS needs application-owned contact verification, consent, professional onboarding and mobile-reservation state. Keycloak remains authentication/credential/email-verification authority, but RAMALS retains only application-required PII.

**Copy PII into journey/evidence/mastery tables for convenience.** Rejected because it violates purpose limitation and creates retention/deletion/audit complexity.

## Consequences

- PR-A must introduce or reuse a separate PII persistence boundary and explicit grants.
- Cross-boundary joins are deliberate rather than implicit through the core learner entity.
- Deletion/export/retention policies can target the PII boundary without changing opaque learner identity semantics.
- Code review can enforce a simple invariant: contact PII columns must not appear on `core.learner`.

## Verification

- Migration/schema test asserts `core.learner` remains free of name/email/mobile/country/city fields.
- DB/application permissions prove only intended components can read/write the contact PII boundary.
- Log/trace/metric scan contains no raw contact PII where not explicitly required.
- AI-plane network/database negative control remains green.
