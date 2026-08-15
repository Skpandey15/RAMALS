package io.ramals.learningplatform.learner;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Learner-supplied goal. Server validates shape here and existence in the service. */
public record LearnerGoalRequest(
    @NotBlank
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "must be an uppercase domain code")
    String targetDomainCode,

    @NotNull
    @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
    @DecimalMax(value = "1", message = "must not exceed 1")
    BigDecimal targetProficiency,

    @FutureOrPresent(message = "must not be in the past")
    LocalDate targetDate) {
}
