package io.ramals.learningplatform.assessment.misconceptiongraph;

import java.util.UUID;

/** A misconception graph edge id that resolves to no row. */
public class MisconceptionGraphEdgeNotFoundException extends RuntimeException {

  public MisconceptionGraphEdgeNotFoundException(UUID id) {
    super("no misconception graph edge with id " + id);
  }
}
