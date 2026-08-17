package io.ramals.learningplatform.ai.contract;

/**
 * Generic classification of a learning domain.
 *
 * <p>Mirrors {@code core.learning_domain.domain_type}. Adding a value is a curriculum governance
 * decision made in a migration, not an application concern — which is why this enum is a projection
 * of the column rather than the other way round.
 */
public enum DomainType {
  TECHNOLOGY,
  ACADEMIC,
  PROFESSIONAL
}
