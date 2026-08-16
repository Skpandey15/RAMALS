# ADR 0001: Learner identity is anchored to the OIDC subject

- **Status:** Accepted
- **Date:** 2026-08-16
- **Tasks:** M0-T07, M0-T18

## Context

The M0-T04 security baseline introduced `LearnerAccessPolicy`, which authorized learner resources by
comparing a path identifier against a custom `learner_id` JWT claim. A code review before M0-T07
found that the shipped Keycloak realm defined exactly one protocol mapper (`ramals-api-audience`) and
**never minted `learner_id`**. The policy therefore evaluated `learnerId.equals(null)` in any real
deployment and denied every learner. Tests passed only because they injected the claim into mock
JWTs, so the gap was invisible to CI.

## Decision

Anchor learner identity to the **`sub` claim**, which OIDC always issues:

- `core.learner` is keyed by an opaque `subject` column and provisioned just-in-time on first
  authenticated contact.
- Every learner-facing endpoint is served under `/api/v1/me/**` and derives the learner from
  `authentication.getName()`. No endpoint accepts a learner identifier from the client.
- M0-T18 additionally added the `learner_id` mapper and an `acr` LoA map to the realm, so the older
  claim-based path works where it is still used.

## Alternatives considered

1. **Add the `learner_id` mapper only.** Restores the original design but keeps ownership dependent
   on realm configuration that can silently drift again — the exact failure just observed.
2. **Keep client-supplied learner ids with an ownership check.** Retains an IDOR-shaped surface that
   must be defended on every endpoint rather than being impossible to express.

## Consequences

- Cross-learner access is prevented **by construction**: there is no addressable path to another
  learner's data, so IDOR/BOLA cannot be reached through the `/me` surface.
- Authorization no longer depends on optional realm configuration.
- The pre-existing `/api/v1/learners/{learnerId}/profile` stub from M0-T04 remains for its security
  contract test and is superseded by `/me/profile`; retiring it is follow-up work.

## Verification

- `LearnerApiContractTests` — ownership derived from subject, not client input.
- `NegativeAuthorizationTests` — cross-learner attempt access returns 404.
- `MvpZeroValidationTests` — full slice runs under a single authenticated subject.
