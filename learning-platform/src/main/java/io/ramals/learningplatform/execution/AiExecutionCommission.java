package io.ramals.learningplatform.execution;

import java.util.Optional;

/** Result of atomically recording an AI request commission. */
public record AiExecutionCommission(
    boolean dispatchAllowed,
    String state,
    Optional<AiExecution> existingExecution) {

  public AiExecutionCommission {
    existingExecution = existingExecution == null ? Optional.empty() : existingExecution;
  }

  public static AiExecutionCommission claimed() {
    return new AiExecutionCommission(true, "STARTED", Optional.empty());
  }

  public static AiExecutionCommission existing(AiExecution execution) {
    return new AiExecutionCommission(false, execution.status(), Optional.of(execution));
  }

  public static AiExecutionCommission inProgress() {
    return new AiExecutionCommission(false, "STARTED", Optional.empty());
  }
}
