package io.ramals.learningplatform.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import java.util.List;
import java.util.Map;

/**
 * The tutor outcome, in the shape the web client already expects.
 *
 * <p>Mirrors the discriminated union on both sides of the wire: {@code outcome} is the discriminator,
 * and "no explanation" and "no explanation <em>because</em>" stay different facts. The client reads
 * {@code outcome === 'UNAVAILABLE'} first and falls through to the proposal otherwise, so the field
 * is always present.
 *
 * <p>Only the validated proposal payload is exposed. The envelope also carries the agent run id,
 * prompt identity, model route, trust level and token usage — operational and audit metadata that
 * belongs in {@code ai_execution} and structured logs, not in a learner's browser. Returning the
 * envelope wholesale would leak the prompt template and version to anybody with a session.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TutorExplainResponse(
    String outcome,
    String explanation,
    List<String> checksForUnderstanding,
    String reason,
    String supportCode) {

  private static final String PROPOSED = "PROPOSED";
  private static final String UNAVAILABLE = "UNAVAILABLE";

  /**
   * Maps a {@link TutorOutcome} onto the wire shape.
   *
   * @param supportCode the interaction id the learner was shown before the request completed. It is
   *     passed in rather than read here so the value on screen and the value returned are the same
   *     identifier, which is the whole point of it.
   */
  public static TutorExplainResponse from(TutorOutcome outcome, String supportCode) {
    return switch (outcome) {
      case TutorOutcome.Unavailable unavailable ->
          new TutorExplainResponse(
              UNAVAILABLE, null, null, unavailable.reason().name(), unavailable.supportCode());
      case TutorOutcome.Proposed proposed -> proposed(proposed.proposal(), supportCode);
    };
  }

  private static TutorExplainResponse proposed(AiProposalEnvelope envelope, String supportCode) {
    Map<String, Object> payload = envelope.proposal() == null ? Map.of() : envelope.proposal();

    // Read defensively. The payload has already passed schema and semantic validation in the AI
    // plane (M1-ADR-004: a proposal that fails validation renders no learner-visible content), but a
    // ClassCastException here would turn a degraded tutor into a 500 for a learner whose session is
    // otherwise fine -- which is the one outcome TutorService exists to prevent.
    String explanation = payload.get("explanation") instanceof String text ? text : "";
    List<String> checks =
        payload.get("checksForUnderstanding") instanceof List<?> items
            ? items.stream().filter(String.class::isInstance).map(String.class::cast).toList()
            : List.of();

    return new TutorExplainResponse(PROPOSED, explanation, checks, null, supportCode);
  }
}
