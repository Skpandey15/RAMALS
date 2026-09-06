package io.ramals.learningplatform.mcp.auth;

import java.time.Instant;
import java.util.Set;

/**
 * M2-ADR-031: the authoritative, independently-validated result of one delegated learner-context
 * token. Only {@link DelegatedLearnerContextValidator} may construct one -- there is no public
 * constructor path from raw, unverified claims to this type, so an application component can never
 * accidentally trust a context it did not itself validate.
 *
 * <p>Carries a capability grant, never learner-state fact: there is no mastery, diagnostic
 * confidence, longitudinal state, or progression field here, by construction (M2-ADR-031 §C). Java
 * still authoritatively computes or reads every fact any future MCP resource returns; this type only
 * ever answers "may this call be attempted", never "what did it return".
 *
 * @param interactionId the M1-ADR-001 correlation id of the authorizing interaction
 * @param learnerScope the opaque learner reference this context was delegated for -- never a raw
 *     database identifier, mirroring {@code LearnerRef}'s own convention
 * @param domainScope the single learning domain code this context is bound to
 * @param capabilities the explicit, non-empty, allowlisted set of MCP capability names this context
 *     may invoke -- never a wildcard; validated against {@link #CAPABILITY_NAME_PATTERN} by
 *     {@link DelegatedLearnerContextValidator} before this record is ever constructed
 * @param issuedAt when Java minted this context
 * @param expiresAt when this context stops being usable -- bounded to the authorizing interaction
 *     (M2-ADR-031 §E), never a general-purpose session length
 */
public record DelegatedLearnerContext(
    String interactionId,
    String learnerScope,
    String domainScope,
    Set<String> capabilities,
    Instant issuedAt,
    Instant expiresAt) {

  /**
   * A capability name's required shape: two lowercase, hyphenated segments joined by a dot (e.g.
   * {@code diagnostics.current-domain-report}). Deliberately excludes {@code *} from every position
   * -- a wildcard cannot match this pattern, so rejecting an unmatched name is sufficient to refuse
   * one; no separate wildcard check is needed anywhere a name is validated against it.
   */
  public static final java.util.regex.Pattern CAPABILITY_NAME_PATTERN =
      java.util.regex.Pattern.compile("^[a-z]+(-[a-z]+)*\\.[a-z]+(-[a-z]+)*$");

  public DelegatedLearnerContext {
    if (interactionId == null || interactionId.isBlank()) {
      throw new IllegalArgumentException("a delegated learner context requires a non-blank interactionId");
    }
    if (learnerScope == null || learnerScope.isBlank()) {
      throw new IllegalArgumentException("a delegated learner context requires a non-blank learner scope");
    }
    if (domainScope == null || domainScope.isBlank()) {
      throw new IllegalArgumentException("a delegated learner context requires a non-blank domain scope");
    }
    if (capabilities == null || capabilities.isEmpty()) {
      throw new IllegalArgumentException("a delegated learner context requires at least one capability");
    }
    for (String capability : capabilities) {
      if (capability == null || !CAPABILITY_NAME_PATTERN.matcher(capability).matches()) {
        throw new IllegalArgumentException(
            "capability name '" + capability + "' does not match the required allowlisted shape");
      }
    }
    capabilities = Set.copyOf(capabilities);
    if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
      throw new IllegalArgumentException("a delegated learner context requires expiresAt after issuedAt");
    }
  }

  /** Whether this context's own capability allowlist includes the named capability. Callers MUST
   * still additionally check the live {@code McpCapabilityRegistry} before dispatch (M2-ADR-031's
   * deny-by-default rule is enforced by both, independently) -- a token claiming a capability the
   * server has never registered must still be refused. */
  public boolean allows(String capabilityName) {
    return capabilities.contains(capabilityName);
  }

  /** Whether this context is bound to the given domain. A learner-scoped MCP capability MUST call
   * this before serving any domain-specific read (M2-ADR-031's domain-scope rule). */
  public boolean permitsDomain(String requestedDomainCode) {
    return domainScope.equalsIgnoreCase(requestedDomainCode);
  }

  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }
}
