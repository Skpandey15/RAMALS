package io.ramals.learningplatform.learning;

import io.ramals.learningplatform.mastery.MasteryStatus;
import org.springframework.stereotype.Component;

/**
 * Deterministic, versioned progression policy. Mastery is preserved once earned: a MASTERED skill
 * stays MASTERED (or RETENTION_DUE) regardless of later prerequisite changes, so historical
 * completion is never re-locked. A not-yet-mastered skill is LOCKED until every prerequisite is
 * currently mastered; if a prerequisite that was previously mastered has since regressed, the block
 * is a REMEDIATION_REQUIRED obligation rather than a plain prerequisite gap. Prerequisite policy is
 * evaluated here, independent of the recommendation action policy.
 */
@Component
public class ProgressionPolicy {

  public static final String POLICY_VERSION = "PROGRESSION_POLICY_V1";

  public ProgressionOutcome decide(
      MasteryStatus masteryStatus,
      boolean prerequisitesReady,
      boolean retentionDue,
      boolean anyPrerequisiteRegressed) {
    if (masteryStatus == MasteryStatus.MASTERED) {
      return retentionDue
          ? new ProgressionOutcome(ProgressionState.RETENTION_DUE, "RETENTION_DUE")
          : new ProgressionOutcome(ProgressionState.MASTERED, "MASTERED");
    }
    if (!prerequisitesReady) {
      return anyPrerequisiteRegressed
          ? new ProgressionOutcome(ProgressionState.LOCKED, "REMEDIATION_REQUIRED")
          : new ProgressionOutcome(ProgressionState.LOCKED, "PREREQUISITE_BLOCKED");
    }
    if (masteryStatus == null || masteryStatus == MasteryStatus.INSUFFICIENT_EVIDENCE) {
      return new ProgressionOutcome(ProgressionState.ELIGIBLE, "PREREQUISITE_READY");
    }
    return new ProgressionOutcome(ProgressionState.NEEDS_PRACTICE, switch (masteryStatus) {
      case NEEDS_RETEACH -> "NEEDS_RETEACH";
      case NEEDS_PRACTICE -> "NEEDS_PRACTICE";
      case DEVELOPING -> "APPROACHING_MASTERY";
      default -> "NEEDS_PRACTICE";
    });
  }
}
