package io.ramals.learningplatform.ai;

import java.util.Set;

/**
 * Least-privilege delegated-context capability allowlists, one per AI operation (MCP-3.1,
 * M2-ADR-031).
 *
 * <p>The five capability name literals mirror {@code McpCapabilityRegistry#mcp2()} and {@code
 * ramals_ai.mcp.client}'s own {@code ALLOWLISTED_CAPABILITIES} exactly -- both already-reviewed,
 * already-shipped sets this class must never drift from, hence the literal duplication rather
 * than importing either (the MCP server registry is a different concern: what the server offers
 * at all, not what one specific outbound call may ask for).
 *
 * <p>Every {@link DelegatedAiExecutionContext} minted through {@link DelegatedAiContextMinter}
 * carries exactly one of the sets below -- never all five, never a wildcard, and never a set this
 * class does not declare. TUTOR and ASSESSMENT AI calls mint no delegated context at all, so they
 * have no entry here at all: an operation with no concrete MCP-3 read need gets nothing, not an
 * empty grant.
 */
public final class AiDelegatedCapabilityPolicy {

  public static final String CURRENT_DOMAIN_REPORT = "diagnostics.current-domain-report";
  public static final String ATTEMPT_REPORT = "diagnostics.attempt-report";
  public static final String LONGITUDINAL_SUMMARY = "diagnostics.longitudinal-summary";
  public static final String MISCONCEPTION_LONGITUDINAL_DETAIL =
      "diagnostics.misconception-longitudinal-detail";
  public static final String MASTERY_CURRENT = "mastery.current";

  /**
   * Granted to the diagnostic assessment AI call -- every H6/H7 read, none of the mastery read: a
   * diagnostic reasoning proposal reasons about evidence, never about a mastery figure it has no
   * concrete role consuming.
   */
  public static final Set<String> DIAGNOSTIC_ASSESSMENT_CAPABILITIES =
      Set.of(CURRENT_DOMAIN_REPORT, ATTEMPT_REPORT, LONGITUDINAL_SUMMARY,
          MISCONCEPTION_LONGITUDINAL_DETAIL);

  /**
   * Granted to the adaptation AI call -- mastery plus the two diagnostic reads it plausibly needs
   * to reason from, never the attempt-scoped or misconception-scoped reads an adaptation decision
   * has no concrete use for.
   */
  public static final Set<String> ADAPTATION_CAPABILITIES =
      Set.of(MASTERY_CURRENT, CURRENT_DOMAIN_REPORT, LONGITUDINAL_SUMMARY);

  private AiDelegatedCapabilityPolicy() {
  }
}
