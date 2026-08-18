package io.ramals.learningplatform.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.ramals.learningplatform.recommendation.RecommendationDecision;
import io.ramals.learningplatform.recommendation.RecommendedAction;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Compares an Adaptation proposal with the deterministic recommendation without delegating
 * authority to the proposal.
 *
 * <p>The recommendation is calculated by {@code RecommendationPolicy} before this gate is called.
 * This class returns that decision in every case, including an invalid or missing AI action. The AI
 * action is research/observability input only.
 */
@Component
public class AdaptationProposalGate {

  private static final Logger LOGGER = LoggerFactory.getLogger(AdaptationProposalGate.class);
  private static final String DISAGREEMENT_METRIC = "ramals.ai.disagreement";

  private final MeterRegistry meterRegistry;

  public AdaptationProposalGate(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public record Proposal(String skillCode, String recommendedAction) {}

  public record Result(RecommendationDecision deterministicDecision, boolean disagreement) {}

  /** Returns the deterministic decision; the proposal can only affect the disagreement signal. */
  public Result compare(RecommendationDecision deterministicDecision, Proposal proposal) {
    RecommendedAction proposed = parseAction(proposal == null ? null : proposal.recommendedAction());
    boolean disagreement = proposed != deterministicDecision.action();
    meterRegistry
        .counter(
            DISAGREEMENT_METRIC,
            "agent", "adaptation",
            "outcome", disagreement ? "disagree" : "agree")
        .increment();

    if (disagreement) {
      LOGGER.atInfo()
          .addKeyValue("operation", "ai.adaptation.compare")
          .addKeyValue("outcome", "disagree")
          .log("adaptation proposal differs from deterministic recommendation");
    }
    return new Result(deterministicDecision, disagreement);
  }

  private static RecommendedAction parseAction(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return RecommendedAction.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
