package io.ramals.learningplatform.mcp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M2-ADR-031: proves the delegated-context issuer and validator round-trip correctly, and that the
 * validator fails closed on every governed negative case -- signature tampering, wrong audience,
 * expiry, missing structural claims, and wildcard capability names.
 *
 * <p>Every token here is validated against the {@code ramals-mcp} audience -- the delegated
 * learner-context credential's own, distinct from the MCP transport's {@code ramals-ai} workload
 * audience (enforced separately by {@link io.ramals.learningplatform.mcp.McpSecurityConfig}, proven
 * by {@link io.ramals.learningplatform.mcp.McpSecurityConfigTests}). {@link
 * #workloadAudiencedTokenCannotBeUsedAsDelegatedContext()} proves that separation from this side: a
 * workload-audienced token is rejected here exactly like any other wrong audience.
 */
class DelegatedLearnerContextValidatorTests {

  // 256-bit test-only keys, generated fresh per JVM run -- never a hardcoded/committed secret.
  private static final byte[] CURRENT_KEY = randomKey();
  private static final byte[] OTHER_KEY = randomKey();

  private static final String ISSUER = "ramals-learning-platform";
  private static final String AUDIENCE = "ramals-mcp";
  private static final Set<String> CAPABILITIES = Set.of("diagnostics.current-domain-report");

  private static byte[] randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return key;
  }

  private DelegatedLearnerContextIssuer issuer(Clock clock) {
    return new DelegatedLearnerContextIssuer(
        ISSUER, AUDIENCE, Duration.ofMinutes(2), "current", CURRENT_KEY, clock);
  }

  private DelegatedLearnerContextValidator validator(Clock clock) {
    return new DelegatedLearnerContextValidator(
        ISSUER, AUDIENCE, Map.of("current", CURRENT_KEY), clock);
  }

  private DelegatedLearnerContextValidator validatorWithRotation(Clock clock) {
    return new DelegatedLearnerContextValidator(
        ISSUER, AUDIENCE, Map.of("current", CURRENT_KEY, "previous", OTHER_KEY), clock);
  }

  // -- positive round trip -----------------------------------------------------------------------

  @Test
  void validRoundTripPreservesInteractionLearnerDomainAndCapabilities() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    String token = issuer(clock).issue("interaction-1", "learner-ref-1", "KAFKA", CAPABILITIES);

    DelegatedLearnerContext context = validator(clock).validate(token);

    assertThat(context.interactionId()).isEqualTo("interaction-1");
    assertThat(context.learnerScope()).isEqualTo("learner-ref-1");
    assertThat(context.domainScope()).isEqualTo("KAFKA");
    assertThat(context.capabilities()).containsExactly("diagnostics.current-domain-report");
    assertThat(context.allows("diagnostics.current-domain-report")).isTrue();
    assertThat(context.allows("diagnostics.attempt-report")).isFalse();
    assertThat(context.permitsDomain("KAFKA")).isTrue();
    assertThat(context.permitsDomain("SPRING_SECURITY")).isFalse();
  }

  @Test
  void previousKeyIsAcceptedDuringARotationWindow() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    DelegatedLearnerContextIssuer previousIssuer = new DelegatedLearnerContextIssuer(
        ISSUER, AUDIENCE, Duration.ofMinutes(2), "previous", OTHER_KEY, clock);
    String token = previousIssuer.issue("interaction-1", "learner-ref-1", "KAFKA", CAPABILITIES);

    DelegatedLearnerContext context = validatorWithRotation(clock).validate(token);

    assertThat(context.interactionId()).isEqualTo("interaction-1");
  }

  // -- negative: signature ------------------------------------------------------------------------

  @Test
  void tamperedSignatureIsRejected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    String token = issuer(clock).issue("interaction-1", "learner-ref-1", "KAFKA", CAPABILITIES);
    String tampered = token.substring(0, token.length() - 4) + "abcd";

    assertThatThrownBy(() -> validator(clock).validate(tampered))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isIn(DelegatedLearnerContextException.Reason.BAD_SIGNATURE,
            DelegatedLearnerContextException.Reason.MALFORMED);
  }

  @Test
  void unknownKeyIdIsRejected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    DelegatedLearnerContextIssuer wrongKeyIssuer = new DelegatedLearnerContextIssuer(
        ISSUER, AUDIENCE, Duration.ofMinutes(2), "not-configured", OTHER_KEY, clock);
    String token = wrongKeyIssuer.issue("interaction-1", "learner-ref-1", "KAFKA", CAPABILITIES);

    assertThatThrownBy(() -> validator(clock).validate(token))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.UNKNOWN_KEY_ID);
  }

  // -- negative: audience --------------------------------------------------------------------------

  /**
   * The two credentials M2-ADR-031 introduces are never substitutable: a workload token proves only
   * "this caller is the authenticated {@code ramals-ai} workload," never "this workload may act for
   * a specific learner." This proves the audience separation from the delegated-context side -- a
   * token bearing the <em>workload</em> audience ({@code ramals-ai}) is rejected here exactly as any
   * other wrong audience would be. {@link
   * io.ramals.learningplatform.mcp.McpSecurityConfigTests#workloadAudienceValidatorAcceptsOnlyTheConfiguredAudience}
   * proves the converse: a token bearing the <em>delegated-context</em> audience ({@code
   * ramals-mcp}) cannot authenticate the MCP transport's own workload leg.
   */
  @Test
  void workloadAudiencedTokenCannotBeUsedAsDelegatedContext() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    DelegatedLearnerContextIssuer wrongAudienceIssuer = new DelegatedLearnerContextIssuer(
        ISSUER, "ramals-ai", Duration.ofMinutes(2), "current", CURRENT_KEY, clock);
    String token = wrongAudienceIssuer.issue("interaction-1", "learner-ref-1", "KAFKA", CAPABILITIES);

    assertThatThrownBy(() -> validator(clock).validate(token))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.WRONG_AUDIENCE);
  }

  @Test
  void wrongIssuerIsRejected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    DelegatedLearnerContextIssuer wrongIssuer = new DelegatedLearnerContextIssuer(
        "someone-else", AUDIENCE, Duration.ofMinutes(2), "current", CURRENT_KEY, clock);
    String token = wrongIssuer.issue("interaction-1", "learner-ref-1", "KAFKA", CAPABILITIES);

    assertThatThrownBy(() -> validator(clock).validate(token))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.WRONG_ISSUER);
  }

  // -- negative: cross-credential substitution ------------------------------------------------------

  /**
   * Test #6 (M2-ADR-031 review): a real Keycloak-shaped workload token -- RS256-signed, {@code
   * aud=ramals-mcp}, {@code azp=ramals-ai-workload} -- cannot substitute for a delegated learner
   * context, even under a deliberate {@code kid} collision with the validator's own configured HMAC
   * key id ("current"). Rejected on the signature check alone: {@link
   * com.nimbusds.jose.crypto.MACVerifier} does not support RS256, so the algorithm mismatch -- not
   * merely an unrecognized key id -- is what defeats it. This is the audience/claims-independent proof
   * that a workload JWT (asymmetric, Keycloak-issued) and a delegated-context JWT (symmetric,
   * Java-self-issued) are never interchangeable, regardless of any claim they might share.
   */
  @Test
  void workloadShapedRs256TokenCannotSubstituteForDelegatedContext() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    KeyPair rsaKeyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

    JWTClaimsSet workloadShapedClaims = new JWTClaimsSet.Builder()
        .issuer("http://localhost:8081/realms/ramals")
        .audience(AUDIENCE)
        .claim("azp", "ramals-ai-workload")
        .issueTime(Date.from(clock.instant()))
        .expirationTime(Date.from(clock.instant().plus(Duration.ofMinutes(2))))
        .build();
    SignedJWT workloadShapedJwt = new SignedJWT(
        // Same "current" kid the delegated-context validator's HMAC key map uses -- deliberately
        // colliding, so a match here would prove the failure was never about the key id at all.
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("current").build(), workloadShapedClaims);
    workloadShapedJwt.sign(new RSASSASigner(rsaKeyPair.getPrivate()));

    assertThatThrownBy(() -> validator(clock).validate(workloadShapedJwt.serialize()))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.BAD_SIGNATURE);
  }

  // -- negative: expiry ------------------------------------------------------------------------------

  @Test
  void expiredContextIsRejected() {
    Instant issuedAt = Instant.parse("2026-09-06T00:00:00Z");
    Clock issueClock = Clock.fixed(issuedAt, ZoneOffset.UTC);
    String token = issuer(issueClock).issue("interaction-1", "learner-ref-1", "KAFKA", CAPABILITIES);

    Clock later = Clock.fixed(issuedAt.plus(Duration.ofMinutes(10)), ZoneOffset.UTC);
    assertThatThrownBy(() -> validator(later).validate(token))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.EXPIRED);
  }

  // -- negative: structural claims -------------------------------------------------------------------

  @Test
  void missingLearnerScopeIsRejected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    String token = signRawClaims(clock, claimsBuilder(clock)
        .claim("interactionId", "interaction-1")
        .claim("domainScope", "KAFKA")
        .claim("capabilities", List.of("diagnostics.current-domain-report"))
        .build());

    assertThatThrownBy(() -> validator(clock).validate(token))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.MISSING_LEARNER_SCOPE);
  }

  @Test
  void missingInteractionIdIsRejected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    String token = signRawClaims(clock, claimsBuilder(clock)
        .claim("learnerScope", "learner-ref-1")
        .claim("domainScope", "KAFKA")
        .claim("capabilities", List.of("diagnostics.current-domain-report"))
        .build());

    assertThatThrownBy(() -> validator(clock).validate(token))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.MISSING_INTERACTION_ID);
  }

  @Test
  void missingDomainScopeIsRejected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    String token = signRawClaims(clock, claimsBuilder(clock)
        .claim("interactionId", "interaction-1")
        .claim("learnerScope", "learner-ref-1")
        .claim("capabilities", List.of("diagnostics.current-domain-report"))
        .build());

    assertThatThrownBy(() -> validator(clock).validate(token))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.MISSING_DOMAIN_SCOPE);
  }

  @Test
  void emptyCapabilitiesIsRejected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    String token = signRawClaims(clock, claimsBuilder(clock)
        .claim("interactionId", "interaction-1")
        .claim("learnerScope", "learner-ref-1")
        .claim("domainScope", "KAFKA")
        .claim("capabilities", List.of())
        .build());

    assertThatThrownBy(() -> validator(clock).validate(token))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.MISSING_CAPABILITIES);
  }

  @Test
  void missingCapabilitiesClaimIsRejected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    String token = signRawClaims(clock, claimsBuilder(clock)
        .claim("interactionId", "interaction-1")
        .claim("learnerScope", "learner-ref-1")
        .claim("domainScope", "KAFKA")
        .build());

    assertThatThrownBy(() -> validator(clock).validate(token))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.MISSING_CAPABILITIES);
  }

  @Test
  void wildcardCapabilityIsRejected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    String token = signRawClaims(clock, claimsBuilder(clock)
        .claim("interactionId", "interaction-1")
        .claim("learnerScope", "learner-ref-1")
        .claim("domainScope", "KAFKA")
        .claim("capabilities", List.of("mcp:*"))
        .build());

    assertThatThrownBy(() -> validator(clock).validate(token))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.INVALID_CAPABILITY_FORMAT);
  }

  @Test
  void prefixWildcardCapabilityIsRejected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    String token = signRawClaims(clock, claimsBuilder(clock)
        .claim("interactionId", "interaction-1")
        .claim("learnerScope", "learner-ref-1")
        .claim("domainScope", "KAFKA")
        .claim("capabilities", List.of("diagnostics.*"))
        .build());

    assertThatThrownBy(() -> validator(clock).validate(token))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.INVALID_CAPABILITY_FORMAT);
  }

  @Test
  void missingTokenIsRejected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    assertThatThrownBy(() -> validator(clock).validate(null))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.MISSING);
    assertThatThrownBy(() -> validator(clock).validate("  "))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.MISSING);
  }

  @Test
  void malformedTokenIsRejected() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    assertThatThrownBy(() -> validator(clock).validate("not-a-jwt-at-all"))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.MALFORMED);
  }

  // -- no raw token in error output -------------------------------------------------------------------

  @Test
  void exceptionNeverCarriesTheRawTokenOrSignature() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
    String secretToken = issuer(clock).issue("interaction-1", "learner-ref-1", "KAFKA", CAPABILITIES);
    String tampered = secretToken.substring(0, secretToken.length() - 4) + "abcd";

    try {
      validator(clock).validate(tampered);
    } catch (DelegatedLearnerContextException failure) {
      assertThat(failure.getMessage()).doesNotContain(tampered);
      assertThat(failure.toString()).doesNotContain(tampered);
      for (StackTraceElement ignored : failure.getStackTrace()) {
        // no-op; presence of a stack trace is fine, it never embeds the token string itself
      }
      return;
    }
    throw new AssertionError("expected DelegatedLearnerContextException");
  }

  // -- helpers -----------------------------------------------------------------------------------

  private JWTClaimsSet.Builder claimsBuilder(Clock clock) {
    Instant now = clock.instant();
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .issueTime(Date.from(now))
        .notBeforeTime(Date.from(now))
        .expirationTime(Date.from(now.plus(Duration.ofMinutes(2))));
  }

  private String signRawClaims(Clock clock, JWTClaimsSet claims) {
    try {
      SignedJWT jwt = new SignedJWT(
          new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("current").build(), claims);
      jwt.sign(new MACSigner(CURRENT_KEY));
      return jwt.serialize();
    } catch (JOSEException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
