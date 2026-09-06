package io.ramals.learningplatform.mcp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M2-ADR-031: {@link DelegatedLearnerContext} cannot be constructed in an invalid shape -- there is
 * no field it could carry that represents authoritative learner state (no such field exists on the
 * type at all), and every structural invariant is enforced in its own compact constructor rather than
 * left to callers to remember.
 */
class DelegatedLearnerContextTests {

  private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
  private static final Instant LATER = NOW.plusSeconds(120);

  @Test
  void wildcardCapabilityCannotBeConstructed() {
    assertThatThrownBy(() -> new DelegatedLearnerContext(
        "interaction-1", "learner-1", "KAFKA", Set.of("mcp:*"), NOW, LATER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void prefixWildcardCapabilityCannotBeConstructed() {
    assertThatThrownBy(() -> new DelegatedLearnerContext(
        "interaction-1", "learner-1", "KAFKA", Set.of("diagnostics.*"), NOW, LATER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void emptyCapabilitySetCannotBeConstructed() {
    assertThatThrownBy(() -> new DelegatedLearnerContext(
        "interaction-1", "learner-1", "KAFKA", Set.of(), NOW, LATER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blankLearnerScopeCannotBeConstructed() {
    assertThatThrownBy(() -> new DelegatedLearnerContext(
        "interaction-1", "  ", "KAFKA", Set.of("diagnostics.current-domain-report"), NOW, LATER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blankDomainScopeCannotBeConstructed() {
    assertThatThrownBy(() -> new DelegatedLearnerContext(
        "interaction-1", "learner-1", " ", Set.of("diagnostics.current-domain-report"), NOW, LATER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blankInteractionIdCannotBeConstructed() {
    assertThatThrownBy(() -> new DelegatedLearnerContext(
        " ", "learner-1", "KAFKA", Set.of("diagnostics.current-domain-report"), NOW, LATER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void expiryNotAfterIssuanceCannotBeConstructed() {
    assertThatThrownBy(() -> new DelegatedLearnerContext(
        "interaction-1", "learner-1", "KAFKA", Set.of("diagnostics.current-domain-report"), NOW, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validShapeConstructsAndExposesOnlyCapabilityGrantFields() {
    DelegatedLearnerContext context = new DelegatedLearnerContext(
        "interaction-1", "learner-1", "KAFKA", Set.of("diagnostics.current-domain-report"), NOW, LATER);

    assertThat(context.isExpired(NOW)).isFalse();
    assertThat(context.isExpired(LATER)).isTrue();
    assertThat(context.isExpired(LATER.plusSeconds(1))).isTrue();

    // Structural proof that the type has no field that could carry authoritative learner-state
    // data: exactly six record components, all identity/scope/capability/timestamp shaped.
    assertThat(DelegatedLearnerContext.class.getRecordComponents()).hasSize(6);
    for (var component : DelegatedLearnerContext.class.getRecordComponents()) {
      assertThat(component.getName()).isIn(
          "interactionId", "learnerScope", "domainScope", "capabilities", "issuedAt", "expiresAt");
    }
  }
}
