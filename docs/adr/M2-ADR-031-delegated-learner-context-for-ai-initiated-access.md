# M2-ADR-031: Delegated learner context for AI-initiated platform access

- **Status:** Proposed
- **Date:** 2026-09-06
- **Relates to:** M1-ADR-001 (interaction classes and deadlines), M1-ADR-003 (workload identity),
  M1-ADR-010 (assessment evaluation is formative-only), M2-ADR-006 (grounded context)
- **Gates:** any MCP server that lets `ramals-ai`/LangGraph call back into the authoritative
  Java learning-platform; does not gate, and is not satisfied by, the existing Java→`ramals-ai`
  workload-identity call direction, which M1-ADR-003 already governs unchanged

## Context

M1-ADR-003 solved one direction: Spring authenticates *to* `ramals-ai` as itself, using a Keycloak
client-credentials grant audienced to `ramals-ai`, so `ramals-ai` can tell "this caller is the
authorized RAMALS core workload" and reject a replayed learner token. That remains correct and is
untouched by this ADR.

MCP introduces the **reverse** direction: `ramals-ai`/LangGraph calling *into* Java to read
learner-scoped authoritative facts (H6, H7, mastery) on behalf of an interaction Java itself already
authorized. A valid `ramals-ai` workload token proves exactly one fact:

> **this caller is the authenticated `ramals-ai` workload.**

It proves nothing about *which learner* that workload may act on behalf of for *this* call. If Java's
MCP surface accepted a workload token plus a caller-supplied `learnerId`/`learnerRef` as sufficient
authorization, the workload's own authentication would be silently repurposed as authorization for an
arbitrary learner — an IDOR / confused-deputy defect, reachable by a compromised, buggy,
manipulated, or simply hallucinating AI workflow, without any RAMALS system being individually wrong.

This ADR governs the security boundary that closes that gap. It is not an MCP architecture ADR: MCP
is the first, but not the only imaginable, consumer of this boundary — the decision here is about
*how a Java-authenticated workload obtains authority to act for one specific, already-authorized
learner interaction*, independent of whatever protocol carries the resulting calls.

### Terms, used precisely and never interchangeably

- **Authentication** — proving *which principal* is making a call (a workload, or, on the unrelated
  learner-facing path, a learner).
- **Authorization** — deciding *what that principal may do*, given who they are.
- **Delegation** — a principal (Java) granting a *different*, more narrowly scoped permission to
  *another* principal (the `ramals-ai` workload) to act *on behalf of* a specific, already-authorized
  interaction — never a grant the second principal can create, extend, or widen for itself.
- **Learner identity** — the opaque reference to the specific learner an interaction concerns
  (`LearnerRef.learnerRef`, unchanged, M1-ADR-003's own convention).
- **Workload identity** — the identity of the `ramals-ai` service process itself (M1-ADR-003's
  `ramals-core-workload` client, `aud=ramals-ai`), unrelated to any learner.
- **Interaction identity** — `interactionId` (M1-ADR-001's own correlation identifier for one
  logical learner action), reused unchanged.
- **Capability scope** — the explicit, allowlisted set of MCP capability names a delegated context
  may invoke (e.g. `diagnostics.attempt-report`) — never a wildcard.
- **Domain scope** — the specific learning domain (`domainCode`, M2-ADR-006's own `DomainContext`)
  a delegated context is bound to.

### Repository evidence inspected before this decision

- **M1-ADR-003** establishes the governing principle this ADR must not contradict without saying so:
  *"[a Spring-signed short-lived JWT] creates a second token issuer with its own signing key and
  rotation story, in a system whose entire identity story is currently one issuer"* — rejected there
  as an alternative to Keycloak-backed **service identity**. Whether that principle also forecloses a
  Java-issued **delegated capability grant** is addressed explicitly below (§ Alternatives, item 4)
  precisely because the two are easy to conflate and this ADR must not conflate them.
- **`SecurityConfig.java`**: one `SecurityFilterChain`, `sessionCreationPolicy(STATELESS)` — RAMALS
  keeps **no server-side session state today**, confirming there is no existing revocation-list or
  session-store mechanism this ADR could reuse for instant token revocation.
- **`application.yml`**: `spring.security.oauth2.resourceserver.jwt.audiences: ${RAMALS_OIDC_AUDIENCE:ramals-api}`
  — Java's own inbound resource server **already** enforces strict audience binding as a native,
  already-proven mechanism (the same mechanism M1-ADR-003 relies on for the outbound direction). This
  is the direct precedent this ADR reuses, not invents.
- **`infrastructure/docker/keycloak/ramals-realm.json`**: each existing client (`ramals-web-ui`,
  `ramals-core-workload`) carries a *static* hardcoded-audience protocol mapper
  (`included.client.audience: ramals-api` / `ramals-ai`, respectively). No `token-exchange` feature is
  configured in this realm. A dynamically-scoped, per-interaction Keycloak-issued token (via RFC 8693
  standard token exchange) is therefore **not a drop-in today** — it would require enabling and
  configuring a currently-absent Keycloak realm feature, a real precondition, not a trivial reuse.
- **`WorkloadTokenProvider.java`**: the existing workload token is a **shared, cached, service-level**
  credential, refreshed ahead of expiry and reused across every call the process makes. Its lifecycle
  is the deliberate opposite of what this ADR needs: a delegated learner-context credential MUST be
  minted fresh per authorized interaction and MUST NOT be cached or reused across learners or
  interactions. Conflating the two lifecycles in one token type would be a defect, not an
  optimization.
- **`DiagnosticDispatchAuthorization` (`fence`, `requestDigest`)**: existing, narrower precedent for
  Java minting its own short-lived, single-purpose authorization artifact for one specific call,
  outside of Keycloak — evidence that "Java issues a narrow, self-verified capability artifact for
  one interaction" is not a novel pattern in this codebase, only a novel *shape* (a signed JWT rather
  than a fence/digest pair).
- **`com.nimbusds:nimbus-jose-jwt`** is already a transitive dependency (pulled in by
  `spring-boot-starter-security-oauth2-resource-server`) and is fully capable of JWT *signing*, not
  only parsing. **A Java-issued delegated-context JWT requires zero new dependency.**
- No existing delegated-context, on-behalf-of, or impersonation mechanism was found anywhere in the
  repository (Java or Python) — this is confirmed net-new, not a duplicate of something that already
  solves this problem.

## Decision

### A. Two independent authorization dimensions, both required

An AI-initiated Java MCP request for a learner-scoped capability MUST present **both**, and neither
substitutes for the other:

1. **Workload authentication** — the existing Keycloak client-credentials token, `aud=ramals-ai`,
   unchanged, reused exactly as M1-ADR-003 already established. Proves *which service* is calling.
2. **Delegated learner-context authorization** — a new, short-lived, signed credential, described
   below. Proves *which already-authorized learner interaction* this specific call may act for, and
   *which capabilities* it may invoke.

A delegated-context credential presented without valid workload authentication MUST fail
(`NOT_AUTHENTICATED`). A valid workload token presented without a required, valid delegated context
MUST fail for any learner-scoped capability (`NOT_AUTHORIZED`). Neither credential is sufficient
alone; this is the whole point of the design, not an incidental detail.

### B. Who issues the delegated-context credential, and why

**Java issues it, self-signed, at the moment it authorizes and dispatches a learner interaction
towards `ramals-ai`.** Java validates its own signature on receipt (HS256, or equivalent, over a
secret held only by the learning-platform process — never distributed to `ramals-ai`, never
committed, provisioned exactly as `RAMALS_AI_WORKLOAD_CLIENT_SECRET` already is).

This is deliberately **not** a second identity issuer in M1-ADR-003's sense, and the distinction is
load-bearing, not semantic:

- M1-ADR-003's rejected alternative replaced Keycloak's role in **service-to-service identity
  federation** — a credential meant to be verified as proof of *who a peer service is*, potentially
  by more than one relying party, with its own rotation and trust-distribution story.
- This credential makes no identity claim at all. It never asserts "I am `ramals-ai`" or "I am
  Java" — both parties' identities are already established by the (unchanged) workload-authentication
  leg. It asserts only "this specific, already-authorized interaction may invoke these specific
  capabilities, for this learner, in this domain, until this time." It has exactly one issuer and
  exactly one verifier — Java, in both roles — and is never checked by, or distributed to, any third
  party. It is closer to a scoped capability grant (or a signed, tamper-evident macaroon) than to an
  identity credential.
- It travels *alongside*, and never replaces or weakens, the existing Keycloak-backed workload
  authentication (§A) — Keycloak's role as the sole identity issuer for *authentication* is
  unchanged.

**Keycloak-issued dynamic delegation (via RFC 8693 standard token exchange) is the architecturally
closer-to-ideal long-term alternative and is recorded as a revisit trigger, not chosen now**,
because it requires enabling and operationally validating a Keycloak realm feature this deployment
does not currently configure — a real precondition this ADR does not paper over (see Alternatives,
item 4, and Revisit triggers).

### C. Credential shape and required claims

The delegated-context token MUST carry, at minimum:
- `iss`: the RAMALS learning-platform (Java), the same principal that also verifies it.
- `aud`: `ramals-mcp` — a new, dedicated audience, distinct from both `ramals-api` (learner-facing)
  and `ramals-ai` (workload authentication). Reuses exactly the audience-separation mechanism
  M1-ADR-003 already relies on and `application.yml`'s existing
  `spring.security.oauth2.resourceserver.jwt.audiences` property already enforces natively.
- `interactionId`: the exact M1-ADR-001 correlation id of the authorizing interaction.
- learner scope: the same opaque `learnerRef` shape M1-ADR-003 already uses for Java→`ramals-ai`
  calls — never a raw database identifier.
- domain scope: `domainCode` (M2-ADR-006), when the interaction is domain-specific.
- capability scope: an explicit array of allowlisted MCP capability names (e.g.
  `["diagnostics.attempt-report", "diagnostics.longitudinal-summary"]`) — never a wildcard such as
  `mcp:*` unless a future, separately governed decision explicitly authorizes one.
- `iat`/`exp`: issued-at and expiry, bounding the token's lifetime to the authorizing interaction
  (§E).

The token MUST NOT carry: mastery values, diagnostic conclusions, G2 evidence, G3 confidence, H5
confidence, H7 state, progression state, unnecessary PII, provider credentials, or any other secret.
It is an authorization capability, never a learner-state snapshot — Java still authoritatively
computes or reads every fact an MCP resource returns; the token only ever gates *whether* a call may
be attempted, never *what* it returns (§F).

Exact JWT claim names beyond the above are an implementation detail for whoever builds the MCP
server, not a decision this ADR fixes.

### D. Learner selection rule

For a learner-scoped MCP capability, the authoritative learner identity **MUST** be derived from the
verified delegated-context token's own learner scope. An MCP request parameter **MUST NOT** be able
to override, supply, or widen it. Concretely:

```
CORRECT:
  MCP request:  diagnostics.current-domain-report { domainCode: "KAFKA" }
  Authorization: learner := delegated_context.learnerScope

FORBIDDEN:
  MCP request:  diagnostics.current-domain-report { learnerId: "<AI-selected>", domainCode: "KAFKA" }
```

If a caller supplies a learner-identifying parameter on a learner-scoped capability at all, the MCP
server MUST reject the request (`INVALID_ARGUMENT`) rather than silently ignore the parameter — a
silently-ignored parameter is exactly the kind of behavior a future, careless client integration
could come to depend on.

### E. Lifetime

The delegated-context token's lifetime **MUST** be bounded by, and MUST NOT outlive, the AI
interaction/workflow for which it was issued — practically, the same order of magnitude as M1-ADR-001's
own `INTERACTIVE_AI`/`LIMITED_DURABLE` deadline classes, never a general-purpose session length. It
**MUST NOT** be usable as a login credential, a refresh token, or a means for the AI workload to
extend its own learner authorization — there is no refresh flow for this credential; a new one is
issued by Java for a new interaction, or not at all.

### F. Authority boundary preserved

The delegated context grants permission to *invoke* a capability. It does not, and structurally
cannot, determine that capability's *result*. `diagnostics.longitudinal-summary` being in a token's
capability scope authorizes the call to be attempted; Java still authoritatively computes/reads H7 on
every call, from its own persisted state, exactly as `LongitudinalEvidenceService` already does today
— the token carries no H7 state and cannot influence what is returned. The same holds for mastery,
G2 evidence, G3 confidence, H5 confidence, progression, and assessment scoring: none is ever sourced
from, or influenced by, the token's own contents.

### G. Admin access — explicitly deferred, not silently assumed

This ADR does **not** extend the delegated-learner-context model to admin authority. A workload token
plus a delegated context, however constructed, **MUST NOT** be treated as admin authorization under
any circumstance this ADR governs. If a future milestone needs AI-initiated admin-scoped MCP access,
that is a separate, higher-risk decision requiring its own explicit governance — recorded here as an
open question (§ Revisit triggers), not answered by omission.

## Alternatives considered

1. **Workload token + caller-supplied `learnerId`.** Rejected. Service authentication answers "who is
   calling," never "which learner may this call act for" — accepting a caller-supplied learner
   identifier as sufficient authorization is exactly the confused-deputy defect this ADR exists to
   prevent.
2. **Opaque `learnerRef` supplied by the AI workload, trusted as authorization by itself.** Rejected.
   Opacity is a privacy property (it prevents the reference from personally identifying a learner
   outside the platform); it is not an authorization property. An opaque reference the AI workload
   itself chooses is exactly as forgeable/confusable as a plain database id would be — hiding the
   shape of the mistake does not remove it.
3. **A server-side opaque interaction/context handle** (Java mints a random handle, stores the
   learner/domain/capability binding server-side, `ramals-ai` presents the bare handle). Valid
   alternative. Benefits: trivial, immediate revocation (delete the row); minimal information
   disclosure (the handle itself carries no claims at all). Costs: reintroduces server-side state
   into a stack whose entire security posture (`SessionCreationPolicy.STATELESS`) is currently
   stateless-by-design, and adds a new piece of shared, horizontally-scaled state (a cache or table)
   purely to support this one flow. Not chosen for Stage-1 because it is a strictly larger
   architectural change (new persistence, new state-sharing concern across instances) than a signed
   token requires, for a benefit (instant revocation) whose absence is otherwise mitigated by a short
   lifetime (§E). Recorded as the natural escalation path if the revocation trade-off (below) proves
   unacceptable in practice.
4. **Keycloak-issued short-lived delegated token via standard token exchange (RFC 8693).**
   Architecturally the closest fit to M1-ADR-003's own "one issuer" principle, and the preferred
   *eventual* answer. Not chosen for Stage-1 because the realm does not currently enable standard
   token exchange (`infrastructure/docker/keycloak/ramals-realm.json` shows only static per-client
   audience mappers), so adopting it now would require a realm-feature change and its own
   operational validation — real scope this ADR does not fold in silently. Recorded as a revisit
   trigger.
5. **Java-self-issued, short-lived, signed delegated learner-context token (this ADR's decision).**
   Accepted. Benefits: stateless (fits the existing `STATELESS` session posture exactly), no new
   shared server-side state, horizontally scalable without coordination, requires zero new dependency
   (`nimbus-jose-jwt` is already present transitively), and — because Java is both sole issuer and
   sole verifier — introduces no new *identity* issuer in M1-ADR-003's sense, only a new *token type*
   for a narrower purpose. Costs: immediate, single-token revocation is harder than with an opaque
   server-side handle (mitigated by a short lifetime, strict audience/capability/interaction binding,
   and the existing broader session/credential-revocation controls the platform already has for
   learner- and workload-level compromise, which remain the correct lever for revoking *those*
   credentials — this ADR does not claim signed tokens provide instant revocation, and no repository
   evidence suggests such a mechanism exists today).

## Security invariants (testable)

Future tests MUST be able to assert, at minimum:

1. Valid workload identity alone cannot access a learner-scoped MCP resource.
2. Valid delegated learner context alone, presented without valid workload authentication, cannot
   access MCP.
3. A learner identity cannot be overridden by an MCP request parameter (§D) — supplying one is a
   rejected request, not a silently-ignored one.
4. A delegated context issued for learner A cannot access learner B's data.
5. A delegated context issued for domain A cannot access domain B unless its domain scope explicitly
   includes it.
6. A delegated context cannot invoke a capability outside its own capability-scope allowlist.
7. An expired delegated context is rejected.
8. A token presented with the wrong audience (e.g. a `ramals-ai` workload token, or a `ramals-api`
   learner token, presented where `ramals-mcp` is required) is rejected.
9. If workload binding is included in the final claim set, a delegated context issued for a different
   workload instance is rejected when presented by another.
10. No MCP capability governed by this ADR can mutate authoritative learner state (H6/H7/mastery/G2/
    G3/H5/progression/assessment scoring all remain exclusively Java-computed, per §F).
11. No authoritative learner-state value is ever sourced from the delegated token's own claims — every
    MCP response is computed fresh from Java's own persisted state.
12. An authorization denial fails closed and produces governed security telemetry (actor identity,
    outcome, capability name — never the raw token, never learner free text) without leaking
    credentials.

## Consequences

- Java gains one new, narrowly-scoped token type and its own signing secret — a new operational
  artifact, but not a new identity provider, a new rotation *ceremony* beyond what already exists for
  comparable secrets (`RAMALS_AI_WORKLOAD_CLIENT_SECRET`'s own precedent), or a new persistence
  requirement.
- Any future MCP (or non-MCP) AI-initiated call into Java's learner-scoped capabilities MUST be
  built against this boundary — a capability that skips it is not a smaller, faster version of the
  same feature; it is the exact defect this ADR exists to prevent.
- Admin-scoped AI-initiated access remains unaddressed and unauthorized until a separate decision
  governs it.
- MCP-1 (the read-only resource foundation) is unblocked to proceed against this boundary; no
  proposal-tool capability may be added until this boundary itself has shipped and been
  security-tested (already MCP Stage-1's own stated scope).

## Revisit triggers

- If Keycloak standard token exchange is ever enabled for another reason, re-evaluate migrating the
  delegated-context credential to a Keycloak-issued one (Alternatives, item 4) — a strictly
  architecturally cleaner fit, once the precondition it depends on is no longer absent.
- If the inability to instantly revoke a single delegated-context token ever becomes an operationally
  demonstrated problem (not merely a theoretical one), escalate to the server-side opaque handle
  design (Alternatives, item 3) rather than shortening lifetimes indefinitely.
- Admin-scoped AI-initiated MCP access requires its own, separate ADR before any admin MCP capability
  is built — this ADR explicitly does not authorize one.
