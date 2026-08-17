-- Give a learning domain a generic type, so the AI boundary can state what kind of domain it is
-- talking about without inventing the answer in application code.
--
-- DomainContext.domainType has to come from somewhere. The alternative was to hardcode it in the
-- Java assembler, which would make the field decorative: it would say TECHNOLOGY because a
-- developer typed TECHNOLOGY, not because the platform knows anything. A column makes it a fact
-- about the domain, resolved the same way domainCode and curriculumVersion already are.
--
-- Deliberately generic and deliberately small. This adds no board, class, degree, semester,
-- programme or career concept; those belong to the MVP-4 catalog model, which is a taxonomy rather
-- than a column. It touches none of the seven frozen deterministic engines and changes no existing
-- semantics -- an additive column on a lookup table.

ALTER TABLE core.learning_domain
  ADD COLUMN domain_type VARCHAR(24) NOT NULL DEFAULT 'TECHNOLOGY';

-- The set is closed on purpose. An open text column would let the first academic domain arrive
-- spelled three different ways, and the AI boundary would have to normalise it forever after.
ALTER TABLE core.learning_domain
  ADD CONSTRAINT ck_learning_domain_type
  CHECK (domain_type IN ('TECHNOLOGY', 'ACADEMIC', 'PROFESSIONAL'));

COMMENT ON COLUMN core.learning_domain.domain_type IS
  'Generic domain classification carried to the AI boundary as DomainContext.domainType. '
  'TECHNOLOGY covers technology skill domains such as KAFKA; ACADEMIC covers formal curricula; '
  'PROFESSIONAL covers role and industry tracks. Adding a value is a curriculum governance '
  'decision, not an application concern.';

-- KAFKA is a technology domain. Stated explicitly rather than left to the column default, so the
-- seeded row does not depend on what the default happens to be at some later date.
UPDATE core.learning_domain SET domain_type = 'TECHNOLOGY' WHERE code = 'KAFKA';
