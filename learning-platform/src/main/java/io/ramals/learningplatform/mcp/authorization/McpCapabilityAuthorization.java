package io.ramals.learningplatform.mcp.authorization;

import io.ramals.learningplatform.mcp.McpCapabilityRegistry;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContext;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator;
import java.util.Locale;
import java.util.UUID;

/**
 * MCP-2 (M2-ADR-031): the one place every learner-scoped MCP tool handler routes through before
 * calling an authoritative Java read service. Deliberately narrow -- authorization only, never a
 * business calculation, never a repository call:
 *
 * <ol>
 *   <li>{@link #authorizeCapability} -- validates the raw delegated-context token (fails closed on
 *       any structural/signature/audience/expiry problem, per {@link DelegatedLearnerContextValidator}),
 *       then independently confirms <b>both</b> the server's own {@link McpCapabilityRegistry} and the
 *       token's own allowlist agree on the exact capability name -- neither is sufficient alone
 *       (mirrors MCP-1's own "both must agree before dispatch" design).
 *   <li>{@link #authorizeDomain} -- for domain-scoped capabilities, confirms the requested domain
 *       exactly matches the token's own domain scope. Never trusts a caller-supplied domain
 *       independently.
 *   <li>{@link #resolveLearnerId} -- the <b>only</b> path from a validated context to an authoritative
 *       learner identity: {@link DelegatedLearnerContext#learnerScope()} parsed as the learner's
 *       internal id. No MCP request parameter ever supplies or overrides a learner identity; this
 *       method is the sole source.
 * </ol>
 * <p>Not a {@code @Component}: it is registered as a {@code @Bean} inside {@code McpServerConfig},
 * which is {@code @ConditionalOnProperty(ramals.mcp.enabled=true)} -- an unconditional {@code
 * @Component} here would be constructed even with MCP disabled, and its constructor requires {@link
 * McpCapabilityRegistry}, which itself only exists when MCP is enabled; that combination would break
 * every unrelated test's application-context startup the moment component scanning found this class.
 */
public class McpCapabilityAuthorization {

  private final McpCapabilityRegistry registry;
  private final DelegatedLearnerContextValidator validator;

  public McpCapabilityAuthorization(
      McpCapabilityRegistry registry, DelegatedLearnerContextValidator validator) {
    this.registry = registry;
    this.validator = validator;
  }

  /**
   * Validates {@code rawDelegatedContextToken} and confirms it authorizes exactly {@code
   * capabilityName}.
   *
   * @throws io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextException if the token itself
   *     is missing, malformed, unsigned by a recognized key, expired, or wrongly audienced/issued
   * @throws McpAuthorizationException {@link McpAuthorizationException.Reason#CAPABILITY_NOT_REGISTERED}
   *     if the server does not offer this capability at all, or {@link
   *     McpAuthorizationException.Reason#CAPABILITY_NOT_DELEGATED} if the token does not allowlist it
   */
  public DelegatedLearnerContext authorizeCapability(
      String rawDelegatedContextToken, String capabilityName) {
    DelegatedLearnerContext context = validator.validate(rawDelegatedContextToken);
    if (!registry.isRegistered(capabilityName)) {
      throw new McpAuthorizationException(McpAuthorizationException.Reason.CAPABILITY_NOT_REGISTERED);
    }
    if (!context.allows(capabilityName)) {
      throw new McpAuthorizationException(McpAuthorizationException.Reason.CAPABILITY_NOT_DELEGATED);
    }
    return context;
  }

  /**
   * For a domain-scoped capability: the requested domain code must exactly match {@code context}'s
   * own domain scope under this platform's one canonical normalization -- {@code
   * toUpperCase(Locale.ROOT)}, the same rule {@code DiagnosticReportService}/{@code
   * LongitudinalEvidenceService} already apply to every domain code before comparing (e.g. {@code
   * DiagnosticReportService.requireDiagnostic}). Deliberately does <b>not</b> call {@link
   * DelegatedLearnerContext#permitsDomain}, which uses {@code String.equalsIgnoreCase} -- a more
   * permissive, less-governed comparison than this codebase's own canonical-uppercase convention (and
   * one Unicode case-folding can make behave surprisingly for non-ASCII input); this method reuses
   * H6/H7's own governed normalization instead of that separate, generic rule.
   *
   * @throws McpAuthorizationException {@link McpAuthorizationException.Reason#DOMAIN_MISMATCH}
   */
  public void authorizeDomain(DelegatedLearnerContext context, String requestedDomainCode) {
    if (requestedDomainCode == null || requestedDomainCode.isBlank()) {
      throw new McpAuthorizationException(McpAuthorizationException.Reason.DOMAIN_MISMATCH);
    }
    String canonicalRequested = requestedDomainCode.toUpperCase(Locale.ROOT);
    String canonicalDelegated = context.domainScope().toUpperCase(Locale.ROOT);
    if (!canonicalDelegated.equals(canonicalRequested)) {
      throw new McpAuthorizationException(McpAuthorizationException.Reason.DOMAIN_MISMATCH);
    }
  }

  /**
   * The sole path from a validated delegated context to an authoritative learner identity. {@code
   * learnerScope} is minted by Java itself as the learner's internal id, rendered as text -- the same
   * convention {@code AdaptationOutboxProcessor} already uses to construct a {@code LearnerRef} for
   * the AI plane.
   *
   * @throws McpAuthorizationException {@link
   *     McpAuthorizationException.Reason#LEARNER_SCOPE_RESOLUTION_FAILURE} if the scope does not
   *     resolve to a well-formed learner id
   */
  public UUID resolveLearnerId(DelegatedLearnerContext context) {
    try {
      return UUID.fromString(context.learnerScope());
    } catch (IllegalArgumentException notAUuid) {
      throw new McpAuthorizationException(
          McpAuthorizationException.Reason.LEARNER_SCOPE_RESOLUTION_FAILURE);
    }
  }
}
