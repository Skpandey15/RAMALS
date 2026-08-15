package io.ramals.learningplatform.curriculum;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CurriculumGraph(
    UUID curriculumVersionId,
    String domainCode,
    String versionCode,
    String status,
    List<SkillNode> skills) {

  public record SkillNode(
      UUID skillId,
      String stableCode,
      String title,
      String description,
      String difficulty,
      BigDecimal targetProficiency,
      int estimatedLearningMinutes,
      BigDecimal masteryThreshold,
      BigDecimal confidenceThreshold,
      int requiredEvidenceCount,
      List<String> acceptedEvidenceTypes,
      List<String> requiredDifficultyBands,
      int displayOrder,
      List<Objective> objectives,
      List<String> prerequisiteSkillCodes) {
  }

  public record Objective(String code, String description, boolean required, int displayOrder) {
  }
}
