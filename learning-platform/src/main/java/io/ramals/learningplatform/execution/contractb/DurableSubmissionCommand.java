package io.ramals.learningplatform.execution.contractb;

import java.util.List;

/**
 * One durable submission, as the platform describes it to the AI plane.
 *
 * @param idempotencyKey server-derived, never caller-supplied, and <strong>not</strong> a claim that
 *     the provider honours it. M2-ADR-016 records that the Message Batches API offers no documented
 *     replay-safe admission; this is a correlation value RAMALS derives and matches on
 */
public record DurableSubmissionCommand(
    String requestId,
    String idempotencyKey,
    String requestDigest,
    String model,
    int maxOutputTokens,
    List<Turn> messages) {

  public DurableSubmissionCommand {
    messages = List.copyOf(messages);
  }

  /** One turn of the conversation. */
  public record Turn(String role, String content) {}
}
