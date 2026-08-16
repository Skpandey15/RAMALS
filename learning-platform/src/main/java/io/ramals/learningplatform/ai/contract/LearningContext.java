package io.ramals.learningplatform.ai.contract;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Minimized, purpose-specific learner state.
 *
 * <p>Decimals cross the wire as strings at a fixed scale. The deterministic engines use BigDecimal
 * at a canonical scale, and a JSON number would invite a float round-trip that breaks exact
 * reproducibility — the property the whole MVP-0 control depends on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LearningContext(
    String skillCode,
    String masteryScore,
    String evidenceConfidence,
    String masteryStatus,
    List<String> prerequisites) {
}
