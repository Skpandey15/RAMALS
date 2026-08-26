package io.ramals.learningplatform.execution;

import java.time.Instant;

/** Grounding identity needed to reconstruct an ownerless diagnostic commission exactly. */
public record DiagnosticCommissionContext(String contextId, Instant asOf) {

  public DiagnosticCommissionContext {
    if (contextId == null || contextId.isBlank() || asOf == null) {
      throw new IllegalArgumentException("diagnostic commission context identity is required");
    }
  }
}
