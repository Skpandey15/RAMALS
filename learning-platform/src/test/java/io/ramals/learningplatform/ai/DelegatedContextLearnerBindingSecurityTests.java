package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContext;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextIssuer;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MCP-3.1 (M2-ADR-031) end-to-end security proof: a delegated learner-context credential minted for
 * one authorized learner's diagnostic assessment or adaptation interaction decodes and validates to
 * that learner's own scope only -- never another's.
 *
 * <p>Deliberately does not mock {@link DelegatedLearnerContextIssuer} or {@link
 * DelegatedLearnerContextValidator}: both run for real here, exactly as they do in production
 * ({@code McpServerConfig} wires the same two classes from the same {@code McpProperties} key
 * material). Only the AI transport/port layer around them is a fixture -- the learner binding
 * itself is proven by an actual sign-then-verify round trip, not assumed.
 */
class DelegatedContextLearnerBindingSecurityTests {

  private static final String ISSUER_NAME = "ramals-learning-platform";
  private static final String AUDIENCE = "ramals-mcp";
  // Random, not a literal -- see DelegatedAiContextMinterTests's own comment on the same pattern.
  private static final byte[] KEY = randomKey();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);

  private static byte[] randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return key;
  }

  private static DelegatedLearnerContextIssuer issuer() {
    return new DelegatedLearnerContextIssuer(
        ISSUER_NAME, AUDIENCE, Duration.ofSeconds(120), "key-1", KEY, CLOCK);
  }

  private static DelegatedLearnerContextValidator validator() {
    return new DelegatedLearnerContextValidator(ISSUER_NAME, AUDIENCE, Map.of("key-1", KEY), CLOCK);
  }

  @Test
  void aDiagnosticAssessmentRequestForLearnerAMintsATokenScopedToLearnerAOnly() {
    DelegatedAiContextMinter minter =
        new DelegatedAiContextMinter(java.util.Optional.of(issuer()));

    // Simulates DiagnosticAssessmentService.assess's own real call shape: interactionId from the
    // authorized interaction, learnerRef the opaque reference GroundingRetrievalService resolved
    // for learner A, domain resolved through CurriculumService.
    DelegatedAiExecutionContext learnerAContext = minter.mint(
        "interaction-for-learner-A", "opaque-ref-learner-A", () -> "KAFKA",
        AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES);

    DelegatedLearnerContext decoded =
        validator().validate(learnerAContext.token().orElseThrow());

    assertThat(decoded.learnerScope()).isEqualTo("opaque-ref-learner-A");
    assertThat(decoded.learnerScope()).isNotEqualTo("opaque-ref-learner-B");
    assertThat(decoded.interactionId()).isEqualTo("interaction-for-learner-A");
  }

  @Test
  void aSeparateAdaptationRequestForLearnerBMintsATokenScopedToLearnerBOnly() {
    DelegatedAiContextMinter minter =
        new DelegatedAiContextMinter(java.util.Optional.of(issuer()));

    // Simulates AdaptationOutboxProcessor's own real call shape: interactionId from the claimed
    // work item, learnerScope the claim's own authoritative learnerId, domain resolved through
    // CurriculumService.domainCodeForSkill.
    DelegatedAiExecutionContext learnerBContext = minter.mint(
        "interaction-for-learner-B", "opaque-ref-learner-B", () -> "KAFKA",
        AiDelegatedCapabilityPolicy.ADAPTATION_CAPABILITIES);

    DelegatedLearnerContext decoded =
        validator().validate(learnerBContext.token().orElseThrow());

    assertThat(decoded.learnerScope()).isEqualTo("opaque-ref-learner-B");
    assertThat(decoded.learnerScope()).isNotEqualTo("opaque-ref-learner-A");
  }

  @Test
  void theTwoLearnersTokensAreNotInterchangeable() {
    DelegatedAiContextMinter minter =
        new DelegatedAiContextMinter(java.util.Optional.of(issuer()));

    DelegatedAiExecutionContext learnerA = minter.mint(
        "interaction-1", "opaque-ref-learner-A", () -> "KAFKA",
        AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES);
    DelegatedAiExecutionContext learnerB = minter.mint(
        "interaction-2", "opaque-ref-learner-B", () -> "KAFKA",
        AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES);

    // Both decode independently -- validating one never confuses it with the other's identity or
    // interaction scope, proving the token itself carries the binding rather than a side channel.
    DelegatedLearnerContext a = validator().validate(learnerA.token().orElseThrow());
    DelegatedLearnerContext b = validator().validate(learnerB.token().orElseThrow());

    assertThat(a.learnerScope()).isNotEqualTo(b.learnerScope());
    assertThat(a.interactionId()).isNotEqualTo(b.interactionId());
    assertThat(learnerA.token()).isNotEqualTo(learnerB.token());
  }
}
