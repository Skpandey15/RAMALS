package io.ramals.learningplatform.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.grounding.GroundedContextItem.ContextAuthority;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GroundingRetrievalServiceTests {
  private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
  private static final UUID LEARNER = UUID.fromString("01920000-0000-7000-8000-000000000001");
  private static final UUID CURRICULUM = UUID.fromString("01900000-0000-7000-8000-000000000002");

  @Test
  void sameAuthorizedStateProducesSameSelectionAndAuditIdentity() {
    FakeRetrieval retrieval = new FakeRetrieval(Optional.of(new AuthorizedGroundingFacts(
        LEARNER, List.of(item("m-1", SourceType.MASTERY),
            item("e-1", SourceType.LEARNER_EVIDENCE),
            item("p-1", SourceType.CURRICULUM_POLICY)))));
    GroundingRetrievalService service = service(retrieval, Clock.fixed(NOW, ZoneOffset.UTC));

    GroundedContext first = service.retrieve("subject-a", CURRICULUM,
        Set.of(SourceType.MASTERY, SourceType.LEARNER_EVIDENCE, SourceType.CURRICULUM_POLICY));
    GroundedContext second = service.retrieve("subject-a", CURRICULUM,
        Set.of(SourceType.MASTERY, SourceType.LEARNER_EVIDENCE, SourceType.CURRICULUM_POLICY));

    assertThat(second).isEqualTo(first);
    assertThat(retrieval.lastSubject).isEqualTo("subject-a");
    assertThat(retrieval.recorded).containsExactly(first, second);
  }

  @Test
  void unknownSubjectAndLatencyOverrunFailClosedWithoutPartialAudit() {
    FakeRetrieval missing = new FakeRetrieval(Optional.empty());
    assertThatThrownBy(() -> service(missing, Clock.fixed(NOW, ZoneOffset.UTC))
        .retrieve("other-subject", CURRICULUM, Set.of()))
        .isInstanceOf(GroundingRetrievalException.class)
        .hasMessage("GROUNDING_LEARNER_NOT_AUTHORIZED");

    FakeRetrieval delayed = new FakeRetrieval(Optional.of(
        new AuthorizedGroundingFacts(LEARNER, List.of(item("m-1", SourceType.MASTERY)))));
    Clock advancing = new Clock() {
      private int reads;
      @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(java.time.ZoneId zone) { return this; }
      @Override public Instant instant() { return NOW.plusSeconds(reads++ * 3L); }
    };
    assertThatThrownBy(() -> service(delayed, advancing)
        .retrieve("subject-a", CURRICULUM, Set.of()))
        .hasMessage("GROUNDING_RETRIEVAL_TIMEOUT");
    assertThat(delayed.recorded).isEmpty();
  }

  @Test
  void policyRejectsAnyConfigurationThatCouldExceedContractLimit() {
    assertThatThrownBy(() -> new GroundingRetrievalPolicy(
        "v", Duration.ofMinutes(1), Duration.ofSeconds(1), 30, 20, 10, 5, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("GROUNDING_RETRIEVAL_POLICY_INVALID");
  }

  private static GroundingRetrievalService service(FakeRetrieval retrieval, Clock clock) {
    GroundedContextValidator validator = new GroundedContextValidator(
        JsonMapper.builder().findAndAddModules().build());
    return new GroundingRetrievalService(
        retrieval, new GroundedContextFactory(validator), GroundingRetrievalPolicy.V1, clock);
  }

  private static GroundedContextItem item(String id, SourceType type) {
    return new GroundedContextItem(id, type, "v1", ContextAuthority.AUTHORITATIVE_FACT,
        "FACT", "value", NOW, null);
  }

  private static final class FakeRetrieval implements GroundingRetrievalPort {
    private final Optional<AuthorizedGroundingFacts> result;
    private final java.util.ArrayList<GroundedContext> recorded = new java.util.ArrayList<>();
    private String lastSubject;

    private FakeRetrieval(Optional<AuthorizedGroundingFacts> result) {
      this.result = result;
    }

    @Override
    public Optional<AuthorizedGroundingFacts> retrieve(
        String authenticatedSubject, UUID curriculumVersionId, Instant asOf,
        GroundingRetrievalPolicy policy) {
      lastSubject = authenticatedSubject;
      return result;
    }

    @Override
    public void appendRetrievalRecord(GroundedContext context, UUID learnerId) {
      recorded.add(context);
    }
  }
}
