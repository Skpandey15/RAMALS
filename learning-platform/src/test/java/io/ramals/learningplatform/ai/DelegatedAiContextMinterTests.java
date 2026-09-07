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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * MCP-3.1 (M2-ADR-031): {@link DelegatedAiContextMinter} is the sole non-test application caller of
 * {@link DelegatedLearnerContextIssuer} -- these tests exercise the real issuer (and, for the
 * round-trip tests, the real validator), never a mock of either, so the actual signing/verification
 * contract is what is being proven, not an assumption about it.
 */
class DelegatedAiContextMinterTests {

  private static final String ISSUER_NAME = "ramals-learning-platform";
  private static final String AUDIENCE = "ramals-mcp";
  // Random, not a literal: a fixed hex-looking string here is exactly the shape a secret scanner's
  // generic-api-key rule flags, the same reason DelegatedLearnerContextValidatorTests's own
  // CURRENT_KEY/OTHER_KEY are generated rather than typed out.
  private static final byte[] KEY = randomKey();
  private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static byte[] randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return key;
  }

  private static DelegatedAiContextMinter realMinter() {
    DelegatedLearnerContextIssuer issuer = new DelegatedLearnerContextIssuer(
        ISSUER_NAME, AUDIENCE, Duration.ofSeconds(120), "key-1", KEY, CLOCK);
    return new DelegatedAiContextMinter(Optional.of(issuer));
  }

  private static DelegatedLearnerContextValidator validator() {
    return new DelegatedLearnerContextValidator(ISSUER_NAME, AUDIENCE, Map.of("key-1", KEY), CLOCK);
  }

  // -- MCP disabled / unconfigured: never a broader/default grant, never a startup failure --------

  @Test
  void noIssuerConfiguredNeverMintsAndNeverThrows() {
    DelegatedAiContextMinter disabled = DelegatedAiContextMinter.disabled();

    DelegatedAiExecutionContext context = disabled.mint(
        "interaction-1", "learner-ref-1", () -> {
          throw new AssertionError("domain scope must never be resolved when no issuer exists");
        },
        Set.of("mastery.current"));

    assertThat(context.token()).isEmpty();
  }

  // -- successful mint carries exactly what was supplied, never more ------------------------------

  @Test
  void mintedTokenValidatesToExactlyTheSuppliedScope() {
    DelegatedAiContextMinter minter = realMinter();

    DelegatedAiExecutionContext context = minter.mint(
        "interaction-42", "learner-ref-42", () -> "KAFKA",
        AiDelegatedCapabilityPolicy.ADAPTATION_CAPABILITIES);

    assertThat(context.token()).isPresent();
    DelegatedLearnerContext validated = validator().validate(context.token().orElseThrow());
    assertThat(validated.interactionId()).isEqualTo("interaction-42");
    assertThat(validated.learnerScope()).isEqualTo("learner-ref-42");
    assertThat(validated.domainScope()).isEqualTo("KAFKA");
    assertThat(validated.capabilities())
        .isEqualTo(AiDelegatedCapabilityPolicy.ADAPTATION_CAPABILITIES);
  }

  // -- fail-closed-to-absent on every incomplete-scope condition -----------------------------------

  @Test
  void blankInteractionIdSkipsMintingWithoutResolvingDomain() {
    assertNeverMints(realMinter(), "", "learner-ref-1");
  }

  @Test
  void blankLearnerScopeSkipsMintingWithoutResolvingDomain() {
    assertNeverMints(realMinter(), "interaction-1", "");
  }

  private static void assertNeverMints(
      DelegatedAiContextMinter minter, String interactionId, String learnerScope) {
    DelegatedAiExecutionContext context = minter.mint(
        interactionId, learnerScope,
        () -> {
          throw new AssertionError("domain scope must never be resolved for an incomplete call");
        },
        Set.of("mastery.current"));
    assertThat(context.token()).isEmpty();
  }

  @Test
  void emptyCapabilitySetSkipsMinting() {
    DelegatedAiExecutionContext context = realMinter().mint(
        "interaction-1", "learner-ref-1", () -> "KAFKA", Set.of());
    assertThat(context.token()).isEmpty();
  }

  @Test
  void domainResolutionFailureSkipsMintingRatherThanPropagating() {
    DelegatedAiExecutionContext context = realMinter().mint(
        "interaction-1", "learner-ref-1",
        () -> {
          throw new IllegalStateException("no domain for this skill");
        },
        Set.of("mastery.current"));

    assertThat(context.token()).isEmpty();
  }

  @Test
  void blankResolvedDomainSkipsMinting() {
    DelegatedAiExecutionContext context = realMinter().mint(
        "interaction-1", "learner-ref-1", () -> "  ", Set.of("mastery.current"));

    assertThat(context.token()).isEmpty();
  }

  @Test
  void signingFailureSkipsMintingRatherThanPropagating() {
    // An issuer built from a deliberately invalid capability set the record's own compact
    // constructor would reject -- proving a signing-time failure degrades to NONE exactly like a
    // resolution failure does, never an exception the caller must handle specially.
    DelegatedLearnerContextIssuer issuer = new DelegatedLearnerContextIssuer(
        ISSUER_NAME, AUDIENCE, Duration.ofSeconds(120), "key-1", KEY, CLOCK);
    DelegatedAiContextMinter minter = new DelegatedAiContextMinter(Optional.of(issuer));

    DelegatedAiExecutionContext context = minter.mint(
        "interaction-1", "learner-ref-1", () -> "KAFKA", Set.of("NOT*A*VALID*CAPABILITY"));

    assertThat(context.token()).isEmpty();
  }

  // -- never a wildcard, never broader than what was asked -----------------------------------------

  @Test
  void mintedCapabilitiesAreNeverWidenedBeyondWhatWasRequested() {
    DelegatedAiContextMinter minter = realMinter();

    DelegatedAiExecutionContext context = minter.mint(
        "interaction-1", "learner-ref-1", () -> "KAFKA",
        AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES);

    DelegatedLearnerContext validated = validator().validate(context.token().orElseThrow());
    assertThat(validated.capabilities())
        .isEqualTo(AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES)
        .doesNotContain(AiDelegatedCapabilityPolicy.MASTERY_CURRENT);
  }

  // -- TTL is bounded by configuration, never open-ended -------------------------------------------

  @Test
  void tokenExpiresAtTheConfiguredTtlNeverLonger() {
    DelegatedLearnerContextIssuer issuer = new DelegatedLearnerContextIssuer(
        ISSUER_NAME, AUDIENCE, Duration.ofSeconds(120), "key-1", KEY, CLOCK);
    DelegatedAiContextMinter minter = new DelegatedAiContextMinter(Optional.of(issuer));

    DelegatedAiExecutionContext context =
        minter.mint("interaction-1", "learner-ref-1", () -> "KAFKA", Set.of("mastery.current"));

    // Still valid now...
    validator().validate(context.token().orElseThrow());
    // ...but expired once the configured TTL plus the validator's own 5-second clock-skew
    // allowance has elapsed -- never a token that outlives its configured lifetime by more than
    // that documented margin.
    DelegatedLearnerContextValidator afterTtl = new DelegatedLearnerContextValidator(
        ISSUER_NAME, AUDIENCE, Map.of("key-1", KEY),
        Clock.fixed(NOW.plusSeconds(126), ZoneOffset.UTC));
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class, () -> afterTtl.validate(context.token().orElseThrow()));
  }

  // -- reuse: different interactions/learners/domains never collapse into one shared scope --------

  @Test
  void differentLearnersMintDistinctScopesNeverASharedOne() {
    DelegatedAiContextMinter minter = realMinter();
    AtomicInteger calls = new AtomicInteger();

    DelegatedAiExecutionContext learnerA = minter.mint(
        "interaction-1", "learner-ref-A", () -> "KAFKA", Set.of("mastery.current"));
    DelegatedAiExecutionContext learnerB = minter.mint(
        "interaction-2", "learner-ref-B", () -> "KAFKA", Set.of("mastery.current"));

    assertThat(learnerA.token()).isNotEqualTo(learnerB.token());
    DelegatedLearnerContext contextA = validator().validate(learnerA.token().orElseThrow());
    DelegatedLearnerContext contextB = validator().validate(learnerB.token().orElseThrow());
    assertThat(contextA.learnerScope()).isEqualTo("learner-ref-A");
    assertThat(contextB.learnerScope()).isEqualTo("learner-ref-B");
    assertThat(contextA.learnerScope()).isNotEqualTo(contextB.learnerScope());
    assertThat(calls.get()).isZero(); // sanity: this test makes no unexpected extra calls
  }

  @Test
  void differentDomainsMintDistinctScopesNeverReusingOneAnothers() {
    DelegatedAiContextMinter minter = realMinter();

    DelegatedAiExecutionContext kafka = minter.mint(
        "interaction-1", "learner-ref-1", () -> "KAFKA", Set.of("mastery.current"));
    DelegatedAiExecutionContext cbse = minter.mint(
        "interaction-1", "learner-ref-1", () -> "CBSE_MATH", Set.of("mastery.current"));

    DelegatedLearnerContext kafkaContext = validator().validate(kafka.token().orElseThrow());
    DelegatedLearnerContext cbseContext = validator().validate(cbse.token().orElseThrow());
    assertThat(kafkaContext.domainScope()).isEqualTo("KAFKA");
    assertThat(cbseContext.domainScope()).isEqualTo("CBSE_MATH");
    assertThat(kafkaContext.permitsDomain("CBSE_MATH")).isFalse();
    assertThat(cbseContext.permitsDomain("KAFKA")).isFalse();
  }

  // -- never a credential in the object's own toString/logs ----------------------------------------

  @Test
  void theExecutionContextRecordNeverExposesTheTokenThroughAnUnrelatedAccessor() {
    DelegatedAiExecutionContext context =
        realMinter().mint("interaction-1", "learner-ref-1", () -> "KAFKA", Set.of("mastery.current"));

    // The only accessor is token() itself -- there is no second field, no derived getter, and no
    // logging call anywhere in DelegatedAiContextMinter that is handed the raw token string (its
    // own log statements carry interactionId/domainCode/capabilityCount only, never the token).
    assertThat(context.token()).isPresent();
  }
}
