package io.ramals.learningplatform.grounding;

import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builds reproducible packages from already-authorized facts; retrieval belongs to M2-T06. */
public final class GroundedContextFactory {

  private final GroundedContextValidator validator;

  public GroundedContextFactory(GroundedContextValidator validator) {
    this.validator = validator;
  }

  /**
   * Mints the durable grounded-context identity.
   *
   * <p>This is the one place a {@code contextId} comes into existence, so it is the one place that
   * decides what the identity is a function of. {@code asOf} is canonicalized to
   * {@link DurableInstant#PRECISION} first: it is hashed into the identity <em>and</em> persisted,
   * and unless those are the same value the identity cannot be reconstructed from the stored row.
   * Canonicalizing at the mint rather than at the caller means no caller can produce a context whose
   * id depends on precision the database will drop.
   */
  public GroundedContext create(String learnerRef, String retrievalPolicyVersion,
      Instant asOf, Duration freshness, List<GroundedContextItem> authorizedItems,
      Set<SourceType> requiredSources) {
    Instant canonicalAsOf = DurableInstant.canonical(asOf);
    List<GroundedContextItem> ordered = authorizedItems.stream()
        .sorted(Comparator.comparing((GroundedContextItem item) -> item.sourceType().name())
            .thenComparing(GroundedContextItem::evidenceId)
            .thenComparing(GroundedContextItem::factType))
        .toList();
    String identity = learnerRef + '|' + retrievalPolicyVersion + '|' + canonicalAsOf + '|'
        + ordered.stream().map(item -> item.sourceType() + ":" + item.evidenceId() + ":"
            + item.sourceVersion() + ":" + item.factType()).reduce("", (a, b) -> a + '|' + b);
    // expiresAt is durable too, and a freshness window could itself carry sub-microsecond parts.
    Instant canonicalExpiresAt = DurableInstant.canonical(canonicalAsOf.plus(freshness));
    GroundedContext context = new GroundedContext(GroundedContext.CONTRACT_VERSION,
        UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(), learnerRef,
        canonicalAsOf, canonicalExpiresAt, retrievalPolicyVersion, ordered);
    validator.validate(context, requiredSources, canonicalAsOf);
    return context;
  }
}
