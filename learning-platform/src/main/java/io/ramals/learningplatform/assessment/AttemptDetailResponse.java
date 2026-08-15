package io.ramals.learningplatform.assessment;

import java.time.Instant;
import java.util.List;

/** Full attempt view including presentable items. Never carries the answer key. */
public record AttemptDetailResponse(
    String attemptId,
    String status,
    String domainCode,
    String assessmentCode,
    String assessmentVersionCode,
    Instant createdAt,
    List<Item> items) {

  public record Item(
      String itemId,
      String itemCode,
      String skillCode,
      String itemType,
      String stem,
      List<DiagnosticItemOption> options,
      int displayOrder) {
  }

  static AttemptDetailResponse from(AttemptDetail detail) {
    AssessmentAttempt attempt = detail.attempt();
    ResolvedDiagnostic diagnostic = detail.diagnostic();
    List<Item> items = detail.items().stream()
        .map(item -> new Item(
            item.id().toString(),
            item.itemCode(),
            item.skillCode(),
            item.itemType(),
            item.stem(),
            item.options(),
            item.displayOrder()))
        .toList();
    return new AttemptDetailResponse(
        attempt.id().toString(),
        attempt.status(),
        diagnostic.domainCode(),
        diagnostic.assessmentCode(),
        diagnostic.versionCode(),
        attempt.createdAt(),
        items);
  }
}
