package io.ramals.learningplatform.assessment.misconceptiongraph;

/**
 * The authored-content lifecycle a misconception graph edge shares with {@code
 * core.diagnostic_probe_relationship} (V054) and {@code core.misconception} (V057): {@code DRAFT}
 * is freely editable, {@code PUBLISHED} is immutable and may reference only published endpoints.
 * There is no {@code RETIRED} state -- nothing depends on retiring an edge yet.
 */
public enum MisconceptionGraphStatus {
  DRAFT,
  PUBLISHED
}
