# M1-ADR-016: Administrative identities use a separate fail-closed application boundary

- **Status:** Accepted
- **Date:** 2026-08-31
- **Relates to:** M1-ADR-003, professional learner registration and verification, Zero Trust UI routing
- **Required before:** Administrative backend integration

## Context

RAMALS now has two distinct interactive application personas in the web UI: a professional learner
and a platform administrator. They share the same OIDC provider and browser application, but they do
not share the same trust boundary or business workflow.

A learner is subject to the professional learner registration and onboarding state machine before the
learning dashboard is available. An administrator must not be provisioned, interpreted, or routed as
a learner merely because the identity is authenticated. Conversely, an `ADMIN` role in the browser
must never be treated as sufficient authorization for an administrative backend operation: the
browser is not a policy enforcement point and is under the user's control.

The previous UI routing correctly failed closed for unsupported roles, but an identity carrying both
`ADMIN` and `LEARNER` was resolved by ordering: `ADMIN` won because it was checked first. That leaves
an ambiguous privileged identity with two application personas and makes future changes to branch
ordering security-significant.

This ADR makes the persona boundary explicit and removes that ambiguity.

## Decision

RAMALS will use **positive, mutually exclusive application-persona routing** for the currently
supported interactive roles.

```text
Authenticated identity
        |
        +-- ADMIN only   ------> Admin application surface
        |
        +-- LEARNER only ------> Learner onboarding -> learner application
        |
        +-- ADMIN + LEARNER ---> DENY
        |
        `-- neither ------------> DENY
```

The routing matrix is normative:

| ADMIN | LEARNER | UI result |
|---|---|---|
| false | false | Deny: no supported application persona |
| true | false | Admin dashboard |
| false | true | Learner onboarding / learner dashboard |
| true | true | Deny: ambiguous privileged persona |

`INSTRUCTOR`, `CONTENT_AUTHOR`, `SERVICE`, future roles, and other roles do not acquire an
application surface merely because they are authenticated. A future UI for any such role requires an
explicit routing decision and tests; it must not appear through a default branch.

### The browser is not an authorization authority

`hasRealmRole(...)` is used only to select an appropriate UI surface. It is a coarse UX routing
signal, not an access-control decision that a backend may trust.

Every protected API remains responsible for validating the bearer token and enforcing the policy for
that operation, including as applicable:

- signature, issuer, audience, expiry, and token/session validity;
- required role, scope, or permission;
- resource ownership and tenant/domain boundaries;
- server-owned lifecycle state and other business invariants; and
- stronger authentication requirements for sensitive operations.

Hiding a button, showing the admin dashboard, or denying a route in React is never evidence that an
operation is authorized.

### Privileged identity separation

For the interactive RAMALS personas covered by this ADR, `ADMIN` and `LEARNER` are mutually
exclusive. An identity carrying both is treated as a policy/configuration error and fails closed in
the UI rather than receiving either surface.

Public professional-learner registration must never be able to request, derive, or grant `ADMIN`.
Registration creates only the fixed learner role defined by the server-side identity provisioning
policy. Administrative role assignment is an out-of-band privileged administration operation.

This ADR does not claim that the UI can detect every possible future or external realm-role
combination. It defines the application roles that currently select RAMALS interactive personas. As
new personas are introduced, the role model and collision policy must be extended explicitly rather
than inferred from ordering.

### Administrative operations

The current admin dashboard is a shell. As its four areas gain functionality, every administrative
API must enforce authorization in the backend independently of the UI. High-impact operations should
also be eligible for stronger controls such as MFA/step-up or recent-authentication requirements at
the identity and backend policy boundary.

The UI may react to a backend challenge or denial; it must not calculate the final risk or grant
itself stronger privileges.

## Alternatives considered

**ADMIN precedence over LEARNER.** Simple and previously implemented. Rejected because a dual-role
identity silently receives the privileged surface and branch order becomes a security policy.

**LEARNER precedence over ADMIN.** Rejected for the same reason and because it can accidentally put a
privileged identity into learner provisioning/onboarding flows.

**Allow dual-role identities and provide a persona switcher.** Potentially useful in a mature
multi-persona product, but rejected for MVP-1. It increases session, authorization, audit, and user
intent complexity. If required later, it needs a separate ADR defining explicit persona selection,
backend policy semantics, and audit evidence.

**Treat any authenticated identity as a learner unless it is ADMIN.** Rejected. Authentication proves
identity; it does not grant a RAMALS application persona. This is incompatible with fail-closed Zero
Trust routing and would expose learner flows to instructor, content-author, service, and future
identities.

**Make the frontend role check authoritative.** Rejected. Browser state and JavaScript are
user-controlled. Backend authorization remains mandatory on every protected operation.

## Consequences

- An account accidentally assigned both `ADMIN` and `LEARNER` sees `Access not configured` and makes
  no learner/admin bootstrap API call from the top-level route.
- Administrators bypass professional learner onboarding because they are a separate persona, not a
  special learner.
- Learners continue through the server-authoritative onboarding state machine before entering the
  learner dashboard.
- Unsupported and future roles fail closed until explicitly designed and tested.
- Keycloak role configuration errors become visible instead of being hidden by route ordering.
- Backend services remain the authoritative policy enforcement points, so bypassing or modifying the
  React UI does not grant administrative access.
- Administrative API delivery must include backend authorization tests; a clickable admin card alone
  is not completion evidence.

## Verification

The web application must carry tests proving that:

- an authenticated `ADMIN`-only identity reaches the admin dashboard and does not start learner API
  bootstrap;
- an authenticated `LEARNER`-only identity remains behind server-authoritative learner onboarding;
- an authenticated identity with both `ADMIN` and `LEARNER` fails closed, reaches neither dashboard,
  and starts no protected learner/admin bootstrap from the top-level route;
- authenticated identities with unsupported roles fail closed;
- an authenticated identity with no supported application role fails closed; and
- unauthenticated registration and login behavior remain unchanged.

Backend administrative endpoints, when introduced, must separately prove authorized `ADMIN` access
and denial for learner, unsupported, missing, expired, wrong-issuer, and wrong-audience credentials as
appropriate to the endpoint. UI tests are not substitutes for those controls.
