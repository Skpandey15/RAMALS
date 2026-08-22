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

  public GroundedContext create(String learnerRef, String retrievalPolicyVersion,
      Instant asOf, Duration freshness, List<GroundedContextItem> authorizedItems,
      Set<SourceType> requiredSources) {
    List<GroundedContextItem> ordered = authorizedItems.stream()
        .sorted(Comparator.comparing((GroundedContextItem item) -> item.sourceType().name())
            .thenComparing(GroundedContextItem::evidenceId)
            .thenComparing(GroundedContextItem::factType))
        .toList();
    String identity = learnerRef + '|' + retrievalPolicyVersion + '|' + asOf + '|'
        + ordered.stream().map(item -> item.sourceType() + ":" + item.evidenceId() + ":"
            + item.sourceVersion() + ":" + item.factType()).reduce("", (a, b) -> a + '|' + b);
    GroundedContext context = new GroundedContext(GroundedContext.CONTRACT_VERSION,
        UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(), learnerRef,
        asOf, asOf.plus(freshness), retrievalPolicyVersion, ordered);
    validator.validate(context, requiredSources, asOf);
    return context;
  }
}
