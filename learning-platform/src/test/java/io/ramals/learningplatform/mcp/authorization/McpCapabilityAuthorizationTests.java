package io.ramals.learningplatform.mcp.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.mcp.McpCapabilityRegistry;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContext;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextException;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextIssuer;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextValidator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MCP-2 (M2-ADR-031): {@link McpCapabilityAuthorization} is the one place every learner-scoped tool
 * handler routes through. These tests exercise it directly, against a real {@link
 * McpCapabilityRegistry#mcp2()} and a real {@link DelegatedLearnerContextValidator}/{@link
 * DelegatedLearnerContextIssuer} pair -- no mocks for the security-critical path.
 *
 * <p>Covers required negative cases 1, 2 (via a missing token), 7, 10, 11, 12 (audience), and 15
 * from the MCP-2 review, plus positive case 3. Cases 4 (wrong workload principal), 6 (learner cannot
 * be supplied in the request), 8 (attempt ownership), 9 (misconception scope), and 13/14
 * (ramals-core-workload / learner-API tokens rejected at the transport layer) are covered elsewhere:
 * 4/13/14 by {@code McpSecurityConfigTests} (the workload transport layer, unchanged by MCP-2); 6 by
 * {@code McpDiagnosticToolsConfigTests}' schema assertions; 8/9 by {@code
 * McpDiagnosticToolsConfigTests}/{@code McpLongitudinalToolsConfigTests}.
 */
class McpCapabilityAuthorizationTests {

  private static final String ISSUER = "ramals-learning-platform";
  private static final String AUDIENCE = "ramals-mcp";
  private static final byte[] KEY = randomKey();
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
  private static final String CAPABILITY = "diagnostics.current-domain-report";

  private static byte[] randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return key;
  }

  private McpCapabilityAuthorization authorization(McpCapabilityRegistry registry) {
    DelegatedLearnerContextValidator validator =
        new DelegatedLearnerContextValidator(ISSUER, AUDIENCE, Map.of("current", KEY), CLOCK);
    return new McpCapabilityAuthorization(registry, validator);
  }

  private String issue(String... capabilities) {
    return new DelegatedLearnerContextIssuer(ISSUER, AUDIENCE, Duration.ofMinutes(2), "current", KEY, CLOCK)
        .issue("interaction-1", UUID.randomUUID().toString(), "KAFKA", Set.of(capabilities));
  }

  // -- test #3: correct learner + correct capability succeeds --------------------------------------

  @Test
  void validTokenWithRegisteredAndDelegatedCapabilitySucceeds() {
    McpCapabilityAuthorization authz = authorization(McpCapabilityRegistry.mcp2());
    String token = issue(CAPABILITY);

    DelegatedLearnerContext context = authz.authorizeCapability(token, CAPABILITY);

    assertThat(context.allows(CAPABILITY)).isTrue();
    assertThat(authz.resolveLearnerId(context)).isNotNull();
  }

  // -- test #15: unknown MCP capability fails -------------------------------------------------------

  @Test
  void unregisteredCapabilityIsDenied() {
    McpCapabilityAuthorization authz = authorization(McpCapabilityRegistry.mcp2());
    // The token itself claims a syntactically valid but never-registered capability -- proves the
    // registry, not just the token's own allowlist, gates dispatch.
    String token = issue("diagnostics.request-probe");

    assertThatThrownBy(() -> authz.authorizeCapability(token, "diagnostics.request-probe"))
        .isInstanceOf(McpAuthorizationException.class)
        .extracting(failure -> ((McpAuthorizationException) failure).reason())
        .isEqualTo(McpAuthorizationException.Reason.CAPABILITY_NOT_REGISTERED);
  }

  @Test
  void emptyRegistryDeniesEveryCapability() {
    McpCapabilityAuthorization authz = authorization(McpCapabilityRegistry.empty());
    String token = issue(CAPABILITY);

    assertThatThrownBy(() -> authz.authorizeCapability(token, CAPABILITY))
        .isInstanceOf(McpAuthorizationException.class)
        .extracting(failure -> ((McpAuthorizationException) failure).reason())
        .isEqualTo(McpAuthorizationException.Reason.CAPABILITY_NOT_REGISTERED);
  }

  // -- test #4 (partial): correct audience is not enough without the specific capability -----------

  @Test
  void registeredCapabilityNotDelegatedByTheTokenIsDenied() {
    McpCapabilityAuthorization authz = authorization(McpCapabilityRegistry.mcp2());
    // Token only allowlists a different (also registered) capability.
    String token = issue("mastery.current");

    assertThatThrownBy(() -> authz.authorizeCapability(token, CAPABILITY))
        .isInstanceOf(McpAuthorizationException.class)
        .extracting(failure -> ((McpAuthorizationException) failure).reason())
        .isEqualTo(McpAuthorizationException.Reason.CAPABILITY_NOT_DELEGATED);
  }

  // -- test #2: delegated-context-only call fails without workload auth ----------------------------
  // (workload auth itself is transport-level and out of this unit's scope; this proves the
  // delegated-context leg alone -- no token at all -- is refused independently.)

  @Test
  void missingDelegatedContextTokenIsRejected() {
    McpCapabilityAuthorization authz = authorization(McpCapabilityRegistry.mcp2());

    assertThatThrownBy(() -> authz.authorizeCapability(null, CAPABILITY))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.MISSING);
  }

  // -- test #7: cross-domain request fails ----------------------------------------------------------

  @Test
  void domainMismatchIsDenied() {
    McpCapabilityAuthorization authz = authorization(McpCapabilityRegistry.mcp2());
    String token = issue(CAPABILITY); // delegated domain is KAFKA
    DelegatedLearnerContext context = authz.authorizeCapability(token, CAPABILITY);

    assertThatThrownBy(() -> authz.authorizeDomain(context, "SPRING_SECURITY"))
        .isInstanceOf(McpAuthorizationException.class)
        .extracting(failure -> ((McpAuthorizationException) failure).reason())
        .isEqualTo(McpAuthorizationException.Reason.DOMAIN_MISMATCH);
  }

  @Test
  void matchingDomainIsAccepted() {
    McpCapabilityAuthorization authz = authorization(McpCapabilityRegistry.mcp2());
    String token = issue(CAPABILITY);
    DelegatedLearnerContext context = authz.authorizeCapability(token, CAPABILITY);

    org.assertj.core.api.Assertions.assertThatCode(() -> authz.authorizeDomain(context, "kafka"))
        .doesNotThrowAnyException();
  }

  // -- test #10: expired delegated context fails ----------------------------------------------------

  @Test
  void expiredDelegatedContextIsRejected() {
    Clock issueClock = CLOCK;
    String token = new DelegatedLearnerContextIssuer(
            ISSUER, AUDIENCE, Duration.ofMinutes(2), "current", KEY, issueClock)
        .issue("interaction-1", UUID.randomUUID().toString(), "KAFKA", Set.of(CAPABILITY));

    Clock laterClock = Clock.fixed(CLOCK.instant().plus(Duration.ofMinutes(10)), ZoneOffset.UTC);
    DelegatedLearnerContextValidator laterValidator =
        new DelegatedLearnerContextValidator(ISSUER, AUDIENCE, Map.of("current", KEY), laterClock);
    McpCapabilityAuthorization authz = new McpCapabilityAuthorization(McpCapabilityRegistry.mcp2(), laterValidator);

    assertThatThrownBy(() -> authz.authorizeCapability(token, CAPABILITY))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.EXPIRED);
  }

  // -- test #11: wrong delegated audience fails -----------------------------------------------------

  @Test
  void wrongDelegatedAudienceIsRejected() {
    McpCapabilityAuthorization authz = authorization(McpCapabilityRegistry.mcp2());
    String wrongAudienceToken = new DelegatedLearnerContextIssuer(
            ISSUER, "ramals-ai", Duration.ofMinutes(2), "current", KEY, CLOCK)
        .issue("interaction-1", UUID.randomUUID().toString(), "KAFKA", Set.of(CAPABILITY));

    assertThatThrownBy(() -> authz.authorizeCapability(wrongAudienceToken, CAPABILITY))
        .isInstanceOf(DelegatedLearnerContextException.class)
        .extracting(failure -> ((DelegatedLearnerContextException) failure).reason())
        .isEqualTo(DelegatedLearnerContextException.Reason.WRONG_AUDIENCE);
  }

  // -- test #12 (delegated-context leg): learner-scope resolution failure --------------------------

  @Test
  void learnerScopeThatIsNotAUuidFailsResolution() {
    // learnerScope must be non-blank at construction (DelegatedLearnerContext's own invariant), but
    // nothing there requires it to be a UUID -- that is exactly what resolveLearnerId itself checks.
    String token = new DelegatedLearnerContextIssuer(ISSUER, AUDIENCE, Duration.ofMinutes(2), "current", KEY, CLOCK)
        .issue("interaction-1", "not-a-uuid", "KAFKA", Set.of(CAPABILITY));
    McpCapabilityAuthorization authz = authorization(McpCapabilityRegistry.mcp2());
    DelegatedLearnerContext context = authz.authorizeCapability(token, CAPABILITY);

    assertThatThrownBy(() -> authz.resolveLearnerId(context))
        .isInstanceOf(McpAuthorizationException.class)
        .extracting(failure -> ((McpAuthorizationException) failure).reason())
        .isEqualTo(McpAuthorizationException.Reason.LEARNER_SCOPE_RESOLUTION_FAILURE);
  }

  @Test
  void wellFormedLearnerScopeResolvesToThatExactId() {
    UUID learnerId = UUID.randomUUID();
    String token = new DelegatedLearnerContextIssuer(ISSUER, AUDIENCE, Duration.ofMinutes(2), "current", KEY, CLOCK)
        .issue("interaction-1", learnerId.toString(), "KAFKA", Set.of(CAPABILITY));
    McpCapabilityAuthorization authz = authorization(McpCapabilityRegistry.mcp2());
    DelegatedLearnerContext context = authz.authorizeCapability(token, CAPABILITY);

    assertThat(authz.resolveLearnerId(context)).isEqualTo(learnerId);
  }

  // -- registry shape itself: exactly five, no wildcard, deny-by-default ---------------------------

  @Test
  void mcp2RegistryContainsExactlyTheFiveGovernedCapabilities() {
    McpCapabilityRegistry registry = McpCapabilityRegistry.mcp2();

    assertThat(registry.registeredCapabilities()).containsExactlyInAnyOrder(
        "diagnostics.current-domain-report",
        "diagnostics.attempt-report",
        "diagnostics.longitudinal-summary",
        "diagnostics.misconception-longitudinal-detail",
        "mastery.current");
    assertThat(registry.isRegistered("diagnostics.*")).isFalse();
    assertThat(registry.isRegistered("*")).isFalse();
    assertThat(registry.isRegistered("diagnostics.request-probe")).isFalse();
    assertThat(registry.isRegistered("tutor.propose")).isFalse();
    assertThat(registry.isRegistered("assessment.evaluate")).isFalse();
  }
}
