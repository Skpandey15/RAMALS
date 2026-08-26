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
    return retrieveAt(
        authenticatedSubject, curriculumVersionId, requiredSources, clock.instant());
  }

  /**
   * Reconstructs a previously commissioned context at its original authoritative timestamp.
   *
   * <p>The timestamp is canonicalized to {@link DurableInstant#PRECISION} before it is used for
   * anything, so a first retrieval and a later reconstruction from the persisted value select
   * against the same instant and mint the same identity. Canonicalizing here rather than at each
   * use also means the retrieval query and the context are pinned to one value, not two.
   */
  public GroundedContext retrieveAt(
      String authenticatedSubject,
      UUID curriculumVersionId,
      Set<SourceType> requiredSources,
      Instant contextAsOf) {
    Instant canonicalAsOf = DurableInstant.canonical(contextAsOf);
    if (authenticatedSubject == null || authenticatedSubject.isBlank()
        || authenticatedSubject.length() > 255 || curriculumVersionId == null
        || canonicalAsOf == null || canonicalAsOf.isAfter(clock.instant())) {
      throw new GroundingRetrievalException("GROUNDING_RETRIEVAL_REQUEST_INVALID");
    }
    Instant retrievalStarted = clock.instant();
    AuthorizedGroundingFacts facts = retrieval.retrieve(
        authenticatedSubject, curriculumVersionId, canonicalAsOf, policy)
        .orElseThrow(() -> new GroundingRetrievalException("GROUNDING_LEARNER_NOT_AUTHORIZED"));
    if (facts.items().isEmpty()) {
      throw new GroundingRetrievalException("GROUNDING_REQUIRED_SOURCE_MISSING");
    }
    if (Duration.between(retrievalStarted, clock.instant()).compareTo(policy.timeout()) > 0) {
      throw new GroundingRetrievalException("GROUNDING_RETRIEVAL_TIMEOUT");
    }
    GroundedContext context = factory.create(
        facts.learnerId().toString(), policy.version(), canonicalAsOf, policy.freshness(),
        facts.items(), Set.copyOf(requiredSources));
    retrieval.appendRetrievalRecord(context, facts.learnerId());
    return context;
  }
}
