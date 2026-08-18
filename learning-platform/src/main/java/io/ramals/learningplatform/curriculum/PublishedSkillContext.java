package io.ramals.learningplatform.curriculum;

/** Authoritative curriculum facts needed to construct an AI request context. */
public record PublishedSkillContext(String domainCode, String domainType, String curriculumVersion) {}
