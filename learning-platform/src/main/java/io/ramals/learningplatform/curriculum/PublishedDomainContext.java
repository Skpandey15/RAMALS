package io.ramals.learningplatform.curriculum;

/**
 * Authoritative curriculum facts for one domain, needed to construct an AI request context when the
 * caller holds a domain code rather than a skill code.
 *
 * <p>The domain-scoped sibling of {@link PublishedSkillContext}. {@code domainType} intentionally
 * remains a string at this boundary, for the same reason: curriculum owns the persisted vocabulary
 * and must not depend on the AI contract package; the AI adapter translates it into its transport
 * enum at the edge.
 *
 * @param domainCode the domain's own stable code
 * @param domainType the persisted {@code core.learning_domain.domain_type} value, verbatim
 * @param curriculumVersion the most recently published curriculum version for the domain, or
 *     {@code null} when the domain has no published version
 */
public record PublishedDomainContext(String domainCode, String domainType, String curriculumVersion) {}
