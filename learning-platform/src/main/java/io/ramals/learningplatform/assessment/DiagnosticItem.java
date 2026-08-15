package io.ramals.learningplatform.assessment;

import java.util.List;
import java.util.UUID;

/**
 * A diagnostic item as presented to the learner. Deliberately omits the answer key, which is never
 * loaded by this read path.
 */
public record DiagnosticItem(
    UUID id,
    String itemCode,
    String skillCode,
    String itemType,
    String stem,
    List<DiagnosticItemOption> options,
    int displayOrder) {
}
