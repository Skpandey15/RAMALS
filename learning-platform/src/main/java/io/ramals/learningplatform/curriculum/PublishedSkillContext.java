package io.ramals.learningplatform.curriculum;

/**
 * Authoritative curriculum facts needed to construct an AI request context.
 *
 * <p>{@code domainType} intentionally remains a string at this boundary. Curriculum owns the
 * persisted vocabulary and must not depend on the AI contract package; the AI adapter translates
 * the authoritative value into its transport enum at the edge.
 */
public record PublishedSkillContext(String domainCode, String domainType, String curriculumVersion) {}
