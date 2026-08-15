# Kafka learning-domain knowledge

The first complete adaptive-learning vertical slice will use Kafka as its subject domain. Versioned curriculum, assessment, and grounded-content assets will be added here by later tasks.

Kafka curriculum v1 is curated and seeded by Flyway migration
`learning-platform/src/main/resources/db/migration/V003__curriculum_and_versioning.sql`.
It is subject-matter data, not a Kafka messaging dependency. Published seed data
must never be edited in place; create a new curriculum version through a forward
migration.
