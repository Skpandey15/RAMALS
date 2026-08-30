# RAMALS Product Vision and Learner Segment Architecture

Status: PRODUCT / ARCHITECTURE DIRECTION
Scope: Long-term RAMALS platform; implementation remains milestone-specific

## 1. Vision

RAMALS is a learning-intelligence platform designed to help a learner move from current capability to a target outcome through evidence-driven, adaptive learning.

The long-term platform may serve:

- professional learners from entry level through highly experienced practitioners;
- higher-education learners such as BTech, BCA and MCA students;
- school learners, initially envisioned for Classes 8–12, including board and entrance preparation.

**Professional is the first product beachhead. It is not the architectural boundary of RAMALS.**

The platform must therefore preserve a common learning substrate while allowing future segment-specific product, pedagogy, compliance and commercial behavior to be introduced only when justified by real product requirements.

## 2. Product promise: LEARN, ADAPT, PROVE

### LEARN

Help the learner acquire the knowledge and skills required for a concrete outcome through curricula, explanations, practice, projects, assessments and AI-assisted learning interactions.

### ADAPT

Continuously use trustworthy evidence to identify gaps, update mastery deterministically, choose appropriate next work, schedule retention and personalize the learning journey.

### PROVE

Produce auditable evidence of what the learner actually demonstrated. Evidence, deterministic mastery and human-governed content provide a trust layer that can support learners and, where product agreements permit, future employers, institutions or certification partners.

For an individual learner, the primary proposition is usually **LEARN + ADAPT to reach an outcome**. For a future B2B/B2B2C buyer, **PROVE** may become the stronger proposition. Evidence is therefore a strategic trust moat, not merely a consumer marketing headline.

## 3. Segment taxonomy

RAMALS recognizes the following long-term product taxonomy:

```text
RAMALS
|
+-- PROFESSIONAL          <- current implementation beachhead
|   +-- technology learning/switching
|   +-- job/role transition
|   +-- interview preparation
|   +-- certification preparation
|   +-- architecture/system-design growth
|   `-- continuing professional development
|
+-- HIGHER_EDUCATION      <- future product segment
|   +-- BTech / BE
|   +-- BCA
|   +-- MCA
|   +-- semester/domain learning
|   +-- placement preparation
|   `-- career readiness
|
`-- SCHOOL                <- future product segment; minor controls required
    +-- Classes 8-12
    +-- board preparation
    `-- entrance preparation
```

This taxonomy is intentionally small. Do not create speculative polymorphic pedagogy engines, guardian workflows, institution hierarchies or segment-specific service abstractions merely because these segment names exist.

**YAGNI applies to segment machinery, not to naming the product boundary.**

## 4. Common platform substrate

The segments are expected to reuse a common conceptual learning loop:

```text
Target outcome
     |
     v
Curriculum / competency model
     |
     v
Diagnostic / prior evidence
     |
     v
Current mastery + skill-gap map
     |
     v
Adaptive learning plan
     |
     v
Learn -> Practice -> Assess
  ^                    |
  |                    v
  +-- Recommendation <- Evidence
                         |
                         v
               Deterministic mastery
                         |
                         v
                Verified outcome proof
```

Common platform capabilities include curriculum/skill graphs, evidence, deterministic mastery, recommendation/policy, assessment, AI assistance under deterministic authority boundaries, auditability, observability and identity/security foundations.

Segment-specific behavior must not weaken the core RAMALS invariant:

> Agents recommend; deterministic services decide.

## 5. Learner segment and age-assurance taxonomy

When a persisted segment discriminator becomes necessary, use the controlled concept:

```text
learner_segment
- PROFESSIONAL
- HIGHER_EDUCATION
- SCHOOL
```

Age/minor handling is a separate concept from product segment. A future compatible taxonomy is:

```text
age_assurance_status
- NOT_REQUIRED
- ADULT_CONFIRMED
- MINOR
- PARENT_VERIFIED
```

For the current professional launch, the intended state is `learner_segment=PROFESSIONAL`. Where product/legal review requires adult assurance, `ADULT_CONFIRMED` may be established by explicit self-attestation such as "I confirm that I am 18 years or older", with auditable version/timestamp evidence.

Do **not** collect date of birth merely to prepare for future segments. Data minimization remains the default.

This document defines taxonomy and future compatibility only. It does not authorize implementation of SCHOOL/HIGHER_EDUCATION onboarding, guardian models, parental-consent flows or minor processing.

## 6. Professional-first product strategy

M1-PROF-01 remains deliberately professional-only. The professional beachhead should prove the complete product loop before RAMALS expands to another segment.

Initial proof should use one coherent domain, currently Kafka, end to end:

`Register -> choose/continue Kafka -> diagnostic -> gap map -> learning plan -> learn/practice -> assess -> evidence -> mastery update -> recommendation -> repeat`

Kafka is a proving domain, not the identity or default of the RAMALS platform.

The product should be tested with real professional learners before generalized segment machinery is built. Qualitative drop-off, completion, trust, usefulness and outcome evidence should drive subsequent product priorities.

## 7. Curriculum authoring and publishing

Curriculum authoring/publishing is a strategic platform capability because a multi-domain, multi-segment startup cannot scale if every new curriculum or skill graph permanently requires a developer-authored Flyway migration.

The target direction is:

```text
Content Author
     |
     v
Curriculum Studio / Authoring API
     |
     v
Domain -> Curriculum -> Skills -> prerequisites
     |
     v
AI-assisted candidate generation
     |
     v
Deterministic structural/policy/safety validation
     |
     v
Human review / promotion where required
     |
     v
Immutable/versioned published curriculum
```

However, generalized curriculum authoring is **not a prerequisite for professional registration**. Sequence it after a complete professional vertical slice has been exercised with real learners unless product evidence demonstrates an earlier blocker.

Published curriculum must retain versioning, provenance, review and rollback semantics appropriate to RAMALS trust requirements.

## 8. Segment expansion rule

A new segment should be activated only when there is a concrete product requirement and its required trust/compliance model is designed and qualified.

Recommended sequence:

1. Professional registration/onboarding.
2. Complete professional vertical slice.
3. Real-user validation.
4. Curriculum authoring/publishing and additional professional domains.
5. Commercial model validation, including individual and possible B2B/B2B2C propositions.
6. Higher Education segment when justified by evidence.
7. School/minor segment only after dedicated product, privacy, age-assurance/consent, safety and legal review.

Do not infer that this sequence permanently fixes business priority; evidence may change ordering. It does fix the rule that unsupported segments are not implemented speculatively.

## 9. Minors and compliance boundary

RAMALS must treat minor support as a distinct future product/security/privacy capability rather than an extension of professional registration.

Before accepting a minor, the applicable release must define and qualify, with appropriate legal/privacy review:

- age-assurance policy;
- parent/guardian consent or other lawful mechanism where required;
- data minimization and retention;
- permitted adaptive/evidence processing;
- guardian visibility and authorization, if applicable;
- content/safety controls;
- deletion/export/account-lifecycle rules;
- jurisdiction-specific requirements.

No current professional milestone should claim that these requirements are solved merely because the taxonomy exists.

## 10. Architecture guardrails

1. Professional-first must never be interpreted as Professional-only platform architecture.
2. Future segment support must not be implemented speculatively in M1-PROF-01.
3. `core.learner` remains the PII-minimized identity anchor according to existing ADRs; segment/profile/contact data belongs in appropriate boundaries.
4. Segment taxonomy does not replace authorization roles. `PROFESSIONAL`, `HIGHER_EDUCATION` and `SCHOOL` are product context, not Keycloak privilege roles.
5. Self-declared profile information is not authoritative mastery.
6. Evidence and deterministic mastery remain authoritative for demonstrated learning state.
7. AI may assist learning/content/recommendation but does not obtain authoritative decision rights merely because a new segment is introduced.
8. Curriculum/content provenance and human-governed trust controls remain applicable where required.
9. Privacy and compliance controls may become stricter by segment; a less restrictive segment must never be used to bypass stricter requirements.
10. Do not collect future-use PII without a present product purpose.

## 11. Relationship to M1-PROF-01

M1-PROF-01 is the first product-segment implementation of this vision.

It remains scoped to professional learners. This document does not add school, college, guardian or minor implementation requirements to that milestone. It clarifies only that implementations should avoid choices that unnecessarily make future segment expansion impossible.

The immediate implementation objective remains a secure, production-grade professional learner flow and a complete professional learning loop.