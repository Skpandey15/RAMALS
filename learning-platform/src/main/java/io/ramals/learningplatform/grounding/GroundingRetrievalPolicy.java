package io.ramals.learningplatform.grounding;

import java.time.Duration;

/** Fixed retrieval bounds; changes require a new policy version for reproducibility. */
public record GroundingRetrievalPolicy(
    String version,
    Duration freshness,
    Duration timeout,
    int evidenceLimit,
    int masteryLimit,
    int skillGraphLimit,
    int curriculumPolicyLimit,
    int approvedContentLimit) {

  public static final GroundingRetrievalPolicy V1 = new GroundingRetrievalPolicy(
      "GROUNDING_RETRIEVAL_V1", Duration.ofMinutes(5), Duration.ofSeconds(2), 20, 12, 12, 8, 8);

  public GroundingRetrievalPolicy {
    if (version == null || version.isBlank() || version.length() > 64
        || freshness == null || freshness.isNegative() || freshness.isZero()
        || timeout == null || timeout.isNegative() || timeout.isZero()
        || evidenceLimit < 0 || masteryLimit < 0 || skillGraphLimit < 0
        || curriculumPolicyLimit < 0 || approvedContentLimit < 0
        || evidenceLimit + masteryLimit + skillGraphLimit + curriculumPolicyLimit
            + approvedContentLimit
            > GroundedContextValidator.MAX_ITEMS) {
      throw new IllegalArgumentException("GROUNDING_RETRIEVAL_POLICY_INVALID");
    }
  }
}
