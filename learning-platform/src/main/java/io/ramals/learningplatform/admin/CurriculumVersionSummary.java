package io.ramals.learningplatform.admin;

import java.time.Instant;
import java.util.UUID;

/** Administrative view of a curriculum version and its lifecycle status. */
public record CurriculumVersionSummary(
    UUID curriculumVersionId,
    String domainCode,
    String versionCode,
    String status,
    Instant publishedAt) {
}
