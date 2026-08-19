package io.ramals.learningplatform.content;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateApprovalRequest(@NotNull UUID candidateId, @Min(1) int candidateRevision) {}
