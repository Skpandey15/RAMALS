package io.ramals.learningplatform.learning;

import java.util.List;

/** Learner-facing progression across a curriculum version's skills. */
public record ProgressionResponse(
    String domainCode,
    String versionCode,
    List<Item> skills) {

  public record Item(
      String skillCode,
      String state,
      String reasonCode,
      String masteryStatus) {
  }

  static ProgressionResponse from(
      String domainCode, String versionCode, List<SkillProgression> progression) {
    return new ProgressionResponse(domainCode, versionCode, progression.stream()
        .map(skill -> new Item(
            skill.skillCode(), skill.state().name(), skill.reasonCode(), skill.masteryStatus()))
        .toList());
  }
}
