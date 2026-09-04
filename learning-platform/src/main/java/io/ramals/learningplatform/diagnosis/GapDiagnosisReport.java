package io.ramals.learningplatform.diagnosis;

import java.util.List;
import java.util.UUID;

/** A learner's gap diagnosis across every skill in one curriculum version. */
public record GapDiagnosisReport(
    UUID learnerId,
    UUID curriculumVersionId,
    List<SkillGapDiagnosis> skills) {
}
