package io.ramals.learningplatform.grounding;

import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import java.math.BigDecimal;
import java.util.Set;

/** Versioned deterministic minimums; confidence supplements evidence and never replaces it. */
public final class ProposalGroundingPolicy {
  public static final String VERSION = "PROPOSAL_GROUNDING_V1";

  public BigDecimal minimumConfidence(ProposalType type) {
    return switch (type) {
      case DIAGNOSTIC -> new BigDecimal("0.6500");
      case ASSESSMENT_EVALUATION -> new BigDecimal("0.7000");
    };
  }

  public Set<SourceType> requiredSources(ProposalType type) {
    return switch (type) {
      case DIAGNOSTIC -> Set.of(
          SourceType.LEARNER_EVIDENCE, SourceType.MASTERY, SourceType.CURRICULUM_POLICY);
      case ASSESSMENT_EVALUATION -> Set.of(
          SourceType.LEARNER_EVIDENCE, SourceType.APPROVED_CONTENT);
    };
  }

  public Set<SourceType> claimEvidenceSources(ProposalType type) {
    return switch (type) {
      case DIAGNOSTIC -> Set.of(SourceType.LEARNER_EVIDENCE, SourceType.MASTERY);
      case ASSESSMENT_EVALUATION -> Set.of(
          SourceType.LEARNER_EVIDENCE, SourceType.APPROVED_CONTENT);
    };
  }
}
