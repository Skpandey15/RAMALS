package io.ramals.learningplatform.mcp.authorization;

/**
 * MCP-2 (M2-ADR-031): raised when a learner-scoped MCP capability call is refused for a reason other
 * than the delegated-context token itself failing validation ({@code
 * io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextException} covers that). This exception
 * covers the authorization decisions layered on top of a token that DID validate: whether the server
 * offers the capability at all, whether this specific token was delegated it, whether the requested
 * domain agrees with the token's own domain scope, whether the learner scope resolves to a real
 * learner, and whether a requested resource (attempt, misconception) is actually owned by that
 * learner.
 *
 * <p>Carries a stable {@link Reason}, never a free-text message that could leak which specific
 * learner/attempt/misconception was denied -- cross-learner denials (test #8/#9) must be
 * indistinguishable from "does not exist at all", the same IDOR-avoidance discipline {@code
 * DiagnosticReportService}/{@code LongitudinalEvidenceService} already apply to their own REST
 * controllers.
 */
public class McpAuthorizationException extends RuntimeException {

  public enum Reason {
    /** The server's own {@code McpCapabilityRegistry} does not offer this capability name at all --
     * independent of what any token claims. */
    CAPABILITY_NOT_REGISTERED,
    /** The registry offers this capability, but the validated delegated context's own allowlist does
     * not include it. */
    CAPABILITY_NOT_DELEGATED,
    /** The requested domain code does not exactly match the delegated context's own domain scope. */
    DOMAIN_MISMATCH,
    /** {@code learnerScope} could not be resolved to an authoritative learner identity. */
    LEARNER_SCOPE_RESOLUTION_FAILURE,
    /** The requested attempt exists, but does not belong to the learner the delegated context
     * resolves to -- reported identically to "attempt not found", never disclosing which. */
    ATTEMPT_NOT_OWNED,
    /** The requested misconception is not accessible in the authorized learner/domain scope. */
    MISCONCEPTION_NOT_ACCESSIBLE,
    /** The request arguments do not match the tool's own declared shape (e.g. a learner-identifying
     * parameter was supplied, or a required field is missing/malformed). */
    MALFORMED_REQUEST
  }

  private final Reason reason;

  public McpAuthorizationException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
