package io.ramals.learningplatform.admin;

import java.time.Instant;

/** Administrative response for a curriculum version. */
public record CurriculumVersionResponse(
    String curriculumVersionId,
    String domainCode,
    String versionCode,
    String status,
    Instant publishedAt) {

  static CurriculumVersionResponse from(CurriculumVersionSummary summary) {
    return new CurriculumVersionResponse(
        summary.curriculumVersionId().toString(),
        summary.domainCode(),
        summary.versionCode(),
        summary.status(),
        summary.publishedAt());
  }
}
