package io.ramals.learningplatform.admin;

public record AdminOperationalSnapshot(
    long learnersTotal,
    long learnersActive,
    long learnersSuspended,
    long learnersClosed,
    long learnersOnboarded,
    long curriculaDraft,
    long curriculaPublished,
    long curriculaRetired,
    long authorizationDenials24h,
    long adminActions24h) {}
