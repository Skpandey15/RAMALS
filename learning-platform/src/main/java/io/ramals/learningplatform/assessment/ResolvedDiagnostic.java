package io.ramals.learningplatform.assessment;

import java.util.UUID;

/** A published diagnostic assessment version resolved for a learning domain. */
public record ResolvedDiagnostic(
    UUID assessmentVersionId,
    String domainCode,
    String assessmentCode,
    String versionCode,
    String status) {
}
