package io.ramals.learningplatform.content;

import java.util.List;
import java.util.UUID;

/**
 * Candidate assessment content awaiting validation.
 *
 * <p>Carries no trust state. Trust is the pipeline's conclusion about content, not a property the
 * content asserts about itself — and a candidate that could arrive claiming to be verified would be
 * a candidate that could be created verified.
 */
public record CandidateContent(
    UUID assessmentVersionId,
    String itemCode,
    String skillCode,
    String objectiveCode,
    String itemType,
    String stem,
    List<String> options,
    List<String> correctOptionIds,
    String difficulty) {
}
