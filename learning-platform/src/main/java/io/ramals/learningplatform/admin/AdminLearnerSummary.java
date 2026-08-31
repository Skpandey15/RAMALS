package io.ramals.learningplatform.admin;

import java.time.Instant;
import java.util.UUID;

public record AdminLearnerSummary(
    UUID learnerId,
    String subject,
    String status,
    String firstName,
    String lastName,
    String email,
    String mobile,
    String countryCode,
    String city,
    boolean emailVerified,
    boolean mobileVerified,
    String onboardingState,
    Instant createdAt,
    Instant updatedAt) {}
