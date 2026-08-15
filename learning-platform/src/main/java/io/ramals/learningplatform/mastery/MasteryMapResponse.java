package io.ramals.learningplatform.mastery;

import java.math.BigDecimal;
import java.util.List;

/** Learner-facing mastery map: score, evidence confidence, and status per skill. */
public record MasteryMapResponse(
    String domainCode,
    String versionCode,
    List<Skill> skills) {

  public record Skill(
      String skillCode,
      BigDecimal masteryScore,
      BigDecimal evidenceConfidence,
      String masteryStatus,
      int aggregateVersion) {
  }

  static MasteryMapResponse from(
      String domainCode, String versionCode, List<MasteryMapEntry> entries) {
    return new MasteryMapResponse(domainCode, versionCode, entries.stream()
        .map(entry -> new Skill(
            entry.skillCode(), entry.masteryScore(), entry.evidenceConfidence(),
            entry.masteryStatus(), entry.aggregateVersion()))
        .toList());
  }
}
