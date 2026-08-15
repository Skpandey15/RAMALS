package io.ramals.learningplatform.learning;

/** Result of starting a session and whether a new one was created (vs. an open one resumed). */
public record SessionStartResult(LearningSession session, boolean created) {
}
