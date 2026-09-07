package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextIssuer;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mints the delegated learner-context credential an outbound Java→ramals-ai call attaches (MCP-3.1,
 * M2-ADR-031), from arguments an authoritative caller has already resolved -- never from a
 * caller-supplied free-text learner, domain or capability value, and never by re-deriving anything
 * this class was not handed.
 *
 * <p>The sole non-test application caller of {@link DelegatedLearnerContextIssuer}: every JWT
 * signing decision stays in that one class, exactly as {@code McpCapabilityAuthorization} is the
 * sole caller of {@code DelegatedLearnerContextValidator} on the receiving side. This class adds no
 * signing logic of its own -- only the fail-closed-to-absent policy around calling it.
 *
 * <p><b>MCP is optional.</b> With no {@link DelegatedLearnerContextIssuer} bean at all (MCP
 * disabled, or its signing key not yet configured -- see {@code McpServerConfig}), every {@link
 * #mint} call returns {@link DelegatedAiExecutionContext#NONE} immediately: the outbound call
 * proceeds exactly as it does today, with no delegated-context header at all, never a startup
 * failure and never a broader/default grant standing in for a real one.
 */
public class DelegatedAiContextMinter {

  private static final Logger LOGGER = LoggerFactory.getLogger(DelegatedAiContextMinter.class);

  private final Optional<DelegatedLearnerContextIssuer> issuer;

  public DelegatedAiContextMinter(Optional<DelegatedLearnerContextIssuer> issuer) {
    this.issuer = issuer == null ? Optional.empty() : issuer;
  }

  /** An instance that never mints -- every call returns {@link DelegatedAiExecutionContext#NONE}.
   * Used where a caller has no issuer to offer (e.g. a test fixture predating MCP-3.1). */
  public static DelegatedAiContextMinter disabled() {
    return new DelegatedAiContextMinter(Optional.empty());
  }

  /**
   * Mints a token scoped to exactly {@code capabilities}, or {@link DelegatedAiExecutionContext#NONE}
   * when minting cannot be attempted or fails for any reason -- never a broader or default grant,
   * never a wildcard scope, and never an exception that could fail the AI call itself: MCP-3
   * capability is additive, not a precondition for the deterministic/AI request to proceed.
   *
   * @param interactionId the already-authorized interaction's own correlation id
   * @param learnerScope the authoritative, already-resolved opaque learner reference -- never a
   *     value this method derives or accepts from an untrusted caller
   * @param domainScope resolves the authoritative domain code for this interaction, evaluated only
   *     if an issuer is actually configured -- a resolution failure here (e.g. no domain found) is
   *     caught the same way a signing failure is, and skips minting rather than propagating
   * @param capabilities the exact, non-empty allowlist for this AI operation (see {@link
   *     AiDelegatedCapabilityPolicy})
   */
  public DelegatedAiExecutionContext mint(
      String interactionId, String learnerScope, Supplier<String> domainScope,
      Set<String> capabilities) {
    if (issuer.isEmpty()) {
      return DelegatedAiExecutionContext.NONE;
    }
    if (interactionId == null || interactionId.isBlank()
        || learnerScope == null || learnerScope.isBlank()
        || capabilities == null || capabilities.isEmpty()) {
      logSkipped(interactionId, "incomplete authorization scope");
      return DelegatedAiExecutionContext.NONE;
    }

    String resolvedDomain;
    try {
      resolvedDomain = domainScope.get();
    } catch (RuntimeException failure) {
      logSkipped(interactionId, "domain scope could not be resolved");
      return DelegatedAiExecutionContext.NONE;
    }
    if (resolvedDomain == null || resolvedDomain.isBlank()) {
      logSkipped(interactionId, "domain scope could not be resolved");
      return DelegatedAiExecutionContext.NONE;
    }

    try {
      String token = issuer.get().issue(interactionId, learnerScope, resolvedDomain, capabilities);
      LOGGER.atInfo()
          .addKeyValue("operation", "ai.delegatedContext.mint")
          .addKeyValue("outcome", "MINTED")
          .addKeyValue("interactionId", interactionId)
          .addKeyValue("domainCode", resolvedDomain)
          .addKeyValue("capabilityCount", capabilities.size())
          .log("delegated learner-context minted for an outbound AI call");
      return new DelegatedAiExecutionContext(Optional.of(token));
    } catch (RuntimeException failure) {
      LOGGER.atWarn()
          .addKeyValue("operation", "ai.delegatedContext.mint")
          .addKeyValue("outcome", "FAILED")
          .addKeyValue("interactionId", interactionId)
          .addKeyValue("errorType", failure.getClass().getSimpleName())
          .log("delegated learner-context minting failed; call proceeds without MCP capability");
      return DelegatedAiExecutionContext.NONE;
    }
  }

  private static void logSkipped(String interactionId, String reason) {
    LOGGER.atWarn()
        .addKeyValue("operation", "ai.delegatedContext.mint")
        .addKeyValue("outcome", "SKIPPED")
        .addKeyValue("interactionId", interactionId)
        .addKeyValue("reason", reason)
        .log("delegated learner-context minting skipped; call proceeds without MCP capability");
  }
}
