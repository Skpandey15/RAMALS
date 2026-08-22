package io.ramals.learningplatform.grounding;

import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Coordinates bounded retrieval and persists the selected source identity set before dispatch. */
public final class GroundingRetrievalService {
  private final GroundingRetrievalPort retrieval;
  private final GroundedContextFactory factory;
  private final GroundingRetrievalPolicy policy;
  private final Clock clock;

  public GroundingRetrievalService(
      GroundingRetrievalPort retrieval,
      GroundedContextFactory factory,
      GroundingRetrievalPolicy policy,
      Clock clock) {
    this.retrieval = retrieval;
    this.factory = factory;
    this.policy = policy;
    this.clock = clock;
  }

  public GroundedContext retrieve(
      String authenticatedSubject,
      UUID curriculumVersionId,
      Set<SourceType> requiredSources) {
    if (authenticatedSubject == null || authenticatedSubject.isBlank()
        || authenticatedSubject.length() > 255 || curriculumVersionId == null) {
      throw new GroundingRetrievalException("GROUNDING_RETRIEVAL_REQUEST_INVALID");
    }
    Instant started = clock.instant();
    AuthorizedGroundingFacts facts = retrieval.retrieve(
        authenticatedSubject, curriculumVersionId, started, policy)
        .orElseThrow(() -> new GroundingRetrievalException("GROUNDING_LEARNER_NOT_AUTHORIZED"));
    if (facts.items().isEmpty()) {
      throw new GroundingRetrievalException("GROUNDING_REQUIRED_SOURCE_MISSING");
    }
    if (Duration.between(started, clock.instant()).compareTo(policy.timeout()) > 0) {
      throw new GroundingRetrievalException("GROUNDING_RETRIEVAL_TIMEOUT");
    }
    GroundedContext context = factory.create(
        facts.learnerId().toString(), policy.version(), started, policy.freshness(), facts.items(),
        Set.copyOf(requiredSources));
    retrieval.appendRetrievalRecord(context, facts.learnerId());
    return context;
  }
}
