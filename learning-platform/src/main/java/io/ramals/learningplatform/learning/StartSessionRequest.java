package io.ramals.learningplatform.learning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Starts (or resumes) a learning session for a curriculum version. */
public record StartSessionRequest(
    @NotBlank
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "must be an uppercase domain code")
    String domainCode,

    @NotBlank
    @Pattern(regexp = "^[a-z0-9][a-z0-9._-]*$", message = "must be a lowercase version code")
    String versionCode) {
}
