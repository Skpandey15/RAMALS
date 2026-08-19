package io.ramals.learningplatform.content;

import jakarta.validation.constraints.NotBlank;

public record RejectApprovalRequest(@NotBlank String reason) {}
