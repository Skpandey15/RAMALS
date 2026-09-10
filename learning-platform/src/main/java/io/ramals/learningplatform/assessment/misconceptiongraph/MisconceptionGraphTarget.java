package io.ramals.learningplatform.assessment.misconceptiongraph;

import io.ramals.learningplatform.assessment.MisconceptionTargetType;
import java.util.UUID;

/**
 * The single authoritative curriculum node a {@link MisconceptionGraphQueryService} request is about
 * (M2-ADR-033 §4). Exactly one node: a {@code LEARNING_OBJECTIVE}, a {@code CONCEPT}, or a {@code
 * SUB_CONCEPT}, identified by its own row id.
 *
 * <p>Reuses {@link MisconceptionTargetType} -- the same enum M2-ADR-026 (H6) already uses for a
 * misconception's exclusive-arc target -- so there is no second node taxonomy. It carries <b>no</b>
 * learner id, interaction id, attempt id, evidence reference, or confidence band: the Step-2 query
 * surface is learner-independent (M2-ADR-033 §5, prompt §15).
 */
public record MisconceptionGraphTarget(MisconceptionTargetType kind, UUID id) {

  public MisconceptionGraphTarget {
    if (kind == null) {
      throw new IllegalArgumentException("misconception graph target kind is required");
    }
    if (id == null) {
      throw new IllegalArgumentException("misconception graph target id is required");
    }
  }
}
