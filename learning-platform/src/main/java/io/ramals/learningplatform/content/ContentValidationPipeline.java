package io.ramals.learningplatform.content;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The staged validation pipeline from M1-ADR-006.
 *
 * <pre>
 *   UNVERIFIED
 *       ↓  structural / schema
 *       ↓  deterministic policy
 *       ↓  quality / safety
 *       ↓  human approval where policy requires
 *   VERIFIED_CONTENT
 * </pre>
 *
 * <p>The single most important property of this class is what it <em>cannot</em> do: it never
 * returns {@link TrustState#VERIFIED_CONTENT}. Passing every automated stage means content has
 * <em>failed to be rejected</em>, which is a weaker statement than having been approved, and the
 * type signature says so — {@link #validate} yields either a rejection or "still unverified, ready
 * for whatever approval the policy requires".
 *
 * <p>Stages run cheapest-first and stop at the first rejection. Ordering is not an optimisation
 * detail: running quality review over a malformed item wastes the expensive stage on an item that
 * was never going to survive the cheap one.
 */
@Component
public class ContentValidationPipeline {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContentValidationPipeline.class);

  private static final String OUTCOME_METRIC = "ramals.content.validation";

  private final List<ContentValidator> validators;
  private final MeterRegistry meterRegistry;

  public ContentValidationPipeline(List<ContentValidator> validators, MeterRegistry meterRegistry) {
    // Sorted by stage declaration order rather than by Spring's bean order, so adding a validator
    // cannot silently change when it runs relative to the others.
    this.validators = validators.stream()
        .sorted(Comparator.comparing(validator -> validator.stage().ordinal()))
        .toList();
    this.meterRegistry = meterRegistry;
  }

  /** What the automated pipeline concluded. Deliberately has no "verified" case. */
  public sealed interface Outcome {

    /**
     * Every automated stage passed. The content is still {@code UNVERIFIED}.
     *
     * <p>Named for what it is rather than "passed", because "passed validation" is the phrase that
     * turns into "therefore promote it" three refactors later.
     */
    record NotRejected() implements Outcome {
    }

    /** A stage refused the content, and which one. */
    record Rejected(ValidationStage stage, String reason) implements Outcome {
    }

    default boolean rejected() {
      return this instanceof Rejected;
    }
  }

  /**
   * Runs the automated stages in order, stopping at the first rejection.
   *
   * @return {@link Outcome.Rejected} naming the stage, or {@link Outcome.NotRejected}. Never a
   *     promotion — promotion is a separate, authenticated act.
   */
  public Outcome validate(CandidateContent candidate, ValidationContext context) {
    for (ContentValidator validator : validators) {
      if (!validator.stage().automated()) {
        // HUMAN_REVIEW is a legitimate rejection stage but not one this pipeline performs. A
        // validator claiming it would be a machine rejecting under a human's name.
        continue;
      }

      Optional<String> refusal = validator.reject(candidate, context);
      if (refusal.isPresent()) {
        meterRegistry
            .counter(OUTCOME_METRIC, "outcome", "rejected", "stage", validator.stage().name())
            .increment();
        LOGGER.atInfo()
            .addKeyValue("operation", "content.validate")
            .addKeyValue("stage", validator.stage().name())
            .addKeyValue("itemCode", candidate.itemCode())
            .addKeyValue("reason", refusal.get())
            .log("candidate content rejected");
        return new Outcome.Rejected(validator.stage(), refusal.get());
      }
    }

    meterRegistry.counter(OUTCOME_METRIC, "outcome", "not_rejected", "stage", "none").increment();
    return new Outcome.NotRejected();
  }

  /** The automated stages actually registered, in execution order. Exposed so a test can assert
   *  the pipeline is the one M1-ADR-006 describes rather than whatever beans happened to exist. */
  public List<ValidationStage> automatedStages() {
    List<ValidationStage> stages = new ArrayList<>();
    for (ContentValidator validator : validators) {
      if (validator.stage().automated()) {
        stages.add(validator.stage());
      }
    }
    return stages;
  }
}
