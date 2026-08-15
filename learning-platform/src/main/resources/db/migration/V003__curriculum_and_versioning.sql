CREATE TABLE core.learning_domain (
  id UUID PRIMARY KEY,
  code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(160) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_learning_domain_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
  CONSTRAINT ck_learning_domain_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE core.curriculum_version (
  id UUID PRIMARY KEY,
  domain_id UUID NOT NULL REFERENCES core.learning_domain(id) ON DELETE RESTRICT,
  version_code VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  published_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (domain_id, version_code),
  CONSTRAINT ck_curriculum_version_code CHECK (version_code ~ '^[a-z0-9][a-z0-9._-]*$'),
  CONSTRAINT ck_curriculum_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
  CONSTRAINT ck_curriculum_publication_time CHECK (
    (status = 'DRAFT' AND published_at IS NULL)
    OR (status IN ('PUBLISHED', 'RETIRED') AND published_at IS NOT NULL)
  )
);

CREATE TABLE core.skill (
  id UUID PRIMARY KEY,
  domain_id UUID NOT NULL REFERENCES core.learning_domain(id) ON DELETE RESTRICT,
  stable_code VARCHAR(96) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (domain_id, stable_code),
  CONSTRAINT ck_skill_stable_code CHECK (stable_code ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE TABLE core.skill_version (
  id UUID PRIMARY KEY,
  skill_id UUID NOT NULL REFERENCES core.skill(id) ON DELETE RESTRICT,
  curriculum_version_id UUID NOT NULL REFERENCES core.curriculum_version(id) ON DELETE RESTRICT,
  title VARCHAR(180) NOT NULL,
  description TEXT NOT NULL,
  difficulty VARCHAR(16) NOT NULL,
  target_proficiency NUMERIC(5, 4) NOT NULL DEFAULT 0.8000,
  estimated_learning_minutes INTEGER NOT NULL,
  mastery_threshold NUMERIC(5, 4) NOT NULL DEFAULT 0.8000,
  confidence_threshold NUMERIC(5, 4) NOT NULL DEFAULT 0.7500,
  required_evidence_count INTEGER NOT NULL DEFAULT 5,
  accepted_evidence_types VARCHAR(32)[] NOT NULL DEFAULT ARRAY['DIAGNOSTIC', 'QUIZ', 'PRACTICE'],
  required_difficulty_bands VARCHAR(16)[] NOT NULL DEFAULT ARRAY[]::VARCHAR(16)[],
  display_order INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (skill_id, curriculum_version_id),
  UNIQUE (curriculum_version_id, display_order),
  CONSTRAINT ck_skill_version_difficulty CHECK (difficulty IN ('FOUNDATIONAL', 'INTERMEDIATE', 'ADVANCED')),
  CONSTRAINT ck_skill_version_target CHECK (target_proficiency > 0 AND target_proficiency <= 1),
  CONSTRAINT ck_skill_version_mastery CHECK (mastery_threshold > 0 AND mastery_threshold <= 1),
  CONSTRAINT ck_skill_version_confidence CHECK (confidence_threshold > 0 AND confidence_threshold <= 1),
  CONSTRAINT ck_skill_version_evidence_count CHECK (required_evidence_count > 0),
  CONSTRAINT ck_skill_version_learning_time CHECK (estimated_learning_minutes > 0),
  CONSTRAINT ck_skill_version_display_order CHECK (display_order > 0),
  CONSTRAINT ck_skill_version_evidence_types CHECK (
    accepted_evidence_types <@ ARRAY['DIAGNOSTIC', 'QUIZ', 'PRACTICE', 'SCENARIO']::VARCHAR(32)[]
    AND cardinality(accepted_evidence_types) > 0
  ),
  CONSTRAINT ck_skill_version_difficulty_bands CHECK (
    required_difficulty_bands <@ ARRAY['EASY', 'MEDIUM', 'HARD']::VARCHAR(16)[]
  )
);

CREATE TABLE core.skill_prerequisite (
  curriculum_version_id UUID NOT NULL REFERENCES core.curriculum_version(id) ON DELETE RESTRICT,
  skill_id UUID NOT NULL,
  prerequisite_skill_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (curriculum_version_id, skill_id, prerequisite_skill_id),
  FOREIGN KEY (skill_id, curriculum_version_id)
    REFERENCES core.skill_version(skill_id, curriculum_version_id) ON DELETE RESTRICT,
  FOREIGN KEY (prerequisite_skill_id, curriculum_version_id)
    REFERENCES core.skill_version(skill_id, curriculum_version_id) ON DELETE RESTRICT,
  CONSTRAINT ck_skill_prerequisite_not_self CHECK (skill_id <> prerequisite_skill_id)
);

CREATE TABLE core.learning_objective (
  id UUID PRIMARY KEY,
  skill_version_id UUID NOT NULL REFERENCES core.skill_version(id) ON DELETE RESTRICT,
  objective_code VARCHAR(96) NOT NULL,
  description TEXT NOT NULL,
  required BOOLEAN NOT NULL DEFAULT TRUE,
  display_order INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (skill_version_id, objective_code),
  UNIQUE (skill_version_id, display_order),
  CONSTRAINT ck_learning_objective_code CHECK (objective_code ~ '^[A-Z][A-Z0-9_]*$'),
  CONSTRAINT ck_learning_objective_order CHECK (display_order > 0)
);

CREATE INDEX idx_curriculum_version_domain_status
  ON core.curriculum_version(domain_id, status, version_code);
CREATE INDEX idx_skill_version_curriculum_order
  ON core.skill_version(curriculum_version_id, display_order, skill_id);
CREATE INDEX idx_skill_prerequisite_target
  ON core.skill_prerequisite(curriculum_version_id, skill_id, prerequisite_skill_id);
CREATE INDEX idx_learning_objective_skill_order
  ON core.learning_objective(skill_version_id, display_order, id);

CREATE FUNCTION core.reject_prerequisite_cycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  IF EXISTS (
    WITH RECURSIVE reachable(skill_id) AS (
      SELECT NEW.prerequisite_skill_id
      UNION
      SELECT edge.prerequisite_skill_id
      FROM core.skill_prerequisite edge
      JOIN reachable current_node ON edge.skill_id = current_node.skill_id
      WHERE edge.curriculum_version_id = NEW.curriculum_version_id
    )
    SELECT 1 FROM reachable WHERE skill_id = NEW.skill_id
  ) THEN
    RAISE EXCEPTION 'prerequisite cycle detected for curriculum version %', NEW.curriculum_version_id
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_skill_prerequisite_cycle
BEFORE INSERT OR UPDATE ON core.skill_prerequisite
FOR EACH ROW EXECUTE FUNCTION core.reject_prerequisite_cycle();

CREATE FUNCTION core.assert_curriculum_editable(version_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
  version_status VARCHAR(16);
BEGIN
  SELECT status INTO version_status FROM core.curriculum_version WHERE id = version_id;
  IF version_status IS NULL THEN
    RAISE EXCEPTION 'curriculum version % does not exist', version_id USING ERRCODE = '23503';
  END IF;
  IF version_status <> 'DRAFT' THEN
    RAISE EXCEPTION 'published or retired curriculum version % is immutable', version_id
      USING ERRCODE = '55000';
  END IF;
END;
$$;

CREATE FUNCTION core.protect_versioned_curriculum_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  version_id UUID;
BEGIN
  IF TG_TABLE_NAME = 'learning_objective' THEN
    SELECT curriculum_version_id INTO version_id
    FROM core.skill_version
    WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.skill_version_id ELSE NEW.skill_version_id END;
  ELSE
    version_id := CASE
      WHEN TG_OP = 'DELETE' THEN OLD.curriculum_version_id
      ELSE NEW.curriculum_version_id
    END;
  END IF;
  PERFORM core.assert_curriculum_editable(version_id);
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_skill_version_immutable
BEFORE INSERT OR UPDATE OR DELETE ON core.skill_version
FOR EACH ROW EXECUTE FUNCTION core.protect_versioned_curriculum_row();
CREATE TRIGGER trg_skill_prerequisite_immutable
BEFORE INSERT OR UPDATE OR DELETE ON core.skill_prerequisite
FOR EACH ROW EXECUTE FUNCTION core.protect_versioned_curriculum_row();
CREATE TRIGGER trg_learning_objective_immutable
BEFORE INSERT OR UPDATE OR DELETE ON core.learning_objective
FOR EACH ROW EXECUTE FUNCTION core.protect_versioned_curriculum_row();

CREATE FUNCTION core.validate_curriculum_publication()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.status = 'DRAFT' AND NEW.status = 'PUBLISHED' THEN
    IF NOT EXISTS (SELECT 1 FROM core.skill_version WHERE curriculum_version_id = NEW.id) THEN
      RAISE EXCEPTION 'curriculum version % has no skills', NEW.id USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
      SELECT 1
      FROM core.skill_version skill_version
      WHERE skill_version.curriculum_version_id = NEW.id
        AND NOT EXISTS (
          SELECT 1 FROM core.learning_objective objective
          WHERE objective.skill_version_id = skill_version.id AND objective.required
        )
    ) THEN
      RAISE EXCEPTION 'every skill version requires at least one required objective'
        USING ERRCODE = '23514';
    END IF;
    NEW.published_at := COALESCE(NEW.published_at, CURRENT_TIMESTAMP);
  ELSIF OLD.status IN ('PUBLISHED', 'RETIRED') THEN
    IF NOT (OLD.status = 'PUBLISHED' AND NEW.status = 'RETIRED'
        AND NEW.domain_id = OLD.domain_id
        AND NEW.version_code = OLD.version_code
        AND NEW.published_at = OLD.published_at
        AND NEW.created_at = OLD.created_at) THEN
      RAISE EXCEPTION 'published curriculum metadata is immutable' USING ERRCODE = '55000';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_curriculum_version_publication
BEFORE UPDATE ON core.curriculum_version
FOR EACH ROW EXECUTE FUNCTION core.validate_curriculum_publication();

INSERT INTO core.learning_domain (id, code, name)
VALUES ('01900000-0000-7000-8000-000000000001', 'KAFKA', 'Apache Kafka');

INSERT INTO core.curriculum_version (id, domain_id, version_code)
VALUES ('01900000-0000-7000-8000-000000000002', '01900000-0000-7000-8000-000000000001', 'v1');

INSERT INTO core.skill (id, domain_id, stable_code) VALUES
('01900000-0000-7000-8000-000000000101', '01900000-0000-7000-8000-000000000001', 'KAFKA_BROKER'),
('01900000-0000-7000-8000-000000000102', '01900000-0000-7000-8000-000000000001', 'KAFKA_TOPIC'),
('01900000-0000-7000-8000-000000000103', '01900000-0000-7000-8000-000000000001', 'KAFKA_PARTITION'),
('01900000-0000-7000-8000-000000000104', '01900000-0000-7000-8000-000000000001', 'KAFKA_RECORD'),
('01900000-0000-7000-8000-000000000105', '01900000-0000-7000-8000-000000000001', 'KAFKA_PRODUCER_SERIALIZATION'),
('01900000-0000-7000-8000-000000000106', '01900000-0000-7000-8000-000000000001', 'KAFKA_PRODUCER_PARTITIONING'),
('01900000-0000-7000-8000-000000000107', '01900000-0000-7000-8000-000000000001', 'KAFKA_PRODUCER_ACKS'),
('01900000-0000-7000-8000-000000000108', '01900000-0000-7000-8000-000000000001', 'KAFKA_PRODUCER_IDEMPOTENCE'),
('01900000-0000-7000-8000-000000000109', '01900000-0000-7000-8000-000000000001', 'KAFKA_CONSUMER_GROUPS'),
('01900000-0000-7000-8000-000000000110', '01900000-0000-7000-8000-000000000001', 'KAFKA_OFFSETS'),
('01900000-0000-7000-8000-000000000111', '01900000-0000-7000-8000-000000000001', 'KAFKA_COMMIT_STRATEGIES'),
('01900000-0000-7000-8000-000000000112', '01900000-0000-7000-8000-000000000001', 'KAFKA_REBALANCING'),
('01900000-0000-7000-8000-000000000113', '01900000-0000-7000-8000-000000000001', 'KAFKA_REPLICATION'),
('01900000-0000-7000-8000-000000000114', '01900000-0000-7000-8000-000000000001', 'KAFKA_ISR'),
('01900000-0000-7000-8000-000000000115', '01900000-0000-7000-8000-000000000001', 'KAFKA_FAILURE_RECOVERY');

INSERT INTO core.skill_version (
  id, skill_id, curriculum_version_id, title, description, difficulty,
  target_proficiency, estimated_learning_minutes, mastery_threshold,
  confidence_threshold, required_evidence_count, required_difficulty_bands, display_order
) VALUES
('01900000-0000-7000-8000-000000000201','01900000-0000-7000-8000-000000000101','01900000-0000-7000-8000-000000000002','Broker','Explain broker responsibilities in a Kafka cluster.','FOUNDATIONAL',0.8000,25,0.8000,0.7500,5,ARRAY['EASY','MEDIUM']::VARCHAR(16)[],1),
('01900000-0000-7000-8000-000000000202','01900000-0000-7000-8000-000000000102','01900000-0000-7000-8000-000000000002','Topic','Model Kafka topics as durable named record streams.','FOUNDATIONAL',0.8000,20,0.8000,0.7500,5,ARRAY['EASY','MEDIUM']::VARCHAR(16)[],2),
('01900000-0000-7000-8000-000000000203','01900000-0000-7000-8000-000000000103','01900000-0000-7000-8000-000000000002','Partition','Explain partition ordering and parallelism tradeoffs.','FOUNDATIONAL',0.8000,35,0.8000,0.7500,5,ARRAY['EASY','MEDIUM']::VARCHAR(16)[],3),
('01900000-0000-7000-8000-000000000204','01900000-0000-7000-8000-000000000104','01900000-0000-7000-8000-000000000002','Record','Identify record key, value, headers and timestamp semantics.','FOUNDATIONAL',0.8000,20,0.8000,0.7500,5,ARRAY['EASY']::VARCHAR(16)[],4),
('01900000-0000-7000-8000-000000000205','01900000-0000-7000-8000-000000000105','01900000-0000-7000-8000-000000000002','Producer Serialization','Select compatible serializers and understand schema impact.','INTERMEDIATE',0.8000,35,0.8000,0.7500,5,ARRAY['EASY','MEDIUM']::VARCHAR(16)[],5),
('01900000-0000-7000-8000-000000000206','01900000-0000-7000-8000-000000000106','01900000-0000-7000-8000-000000000002','Producer Partitioning','Predict key-based producer partition selection.','INTERMEDIATE',0.8000,35,0.8000,0.7500,5,ARRAY['MEDIUM']::VARCHAR(16)[],6),
('01900000-0000-7000-8000-000000000207','01900000-0000-7000-8000-000000000107','01900000-0000-7000-8000-000000000002','Producer ACKs','Compare acknowledgement modes and durability tradeoffs.','INTERMEDIATE',0.8000,40,0.8000,0.7500,5,ARRAY['MEDIUM','HARD']::VARCHAR(16)[],7),
('01900000-0000-7000-8000-000000000208','01900000-0000-7000-8000-000000000108','01900000-0000-7000-8000-000000000002','Producer Idempotence','Explain duplicate prevention and producer idempotence constraints.','INTERMEDIATE',0.8500,45,0.8500,0.8000,6,ARRAY['MEDIUM','HARD']::VARCHAR(16)[],8),
('01900000-0000-7000-8000-000000000209','01900000-0000-7000-8000-000000000109','01900000-0000-7000-8000-000000000002','Consumer Groups','Explain group membership and partition assignment.','INTERMEDIATE',0.8000,45,0.8000,0.7500,5,ARRAY['EASY','MEDIUM']::VARCHAR(16)[],9),
('01900000-0000-7000-8000-000000000210','01900000-0000-7000-8000-000000000110','01900000-0000-7000-8000-000000000002','Offsets','Interpret consumer offsets and delivery position.','INTERMEDIATE',0.8000,35,0.8000,0.7500,5,ARRAY['EASY','MEDIUM']::VARCHAR(16)[],10),
('01900000-0000-7000-8000-000000000211','01900000-0000-7000-8000-000000000111','01900000-0000-7000-8000-000000000002','Commit Strategies','Compare automatic and manual offset commit strategies.','INTERMEDIATE',0.8000,45,0.8000,0.7500,5,ARRAY['MEDIUM','HARD']::VARCHAR(16)[],11),
('01900000-0000-7000-8000-000000000212','01900000-0000-7000-8000-000000000112','01900000-0000-7000-8000-000000000002','Rebalancing','Diagnose triggers and impact of consumer-group rebalancing.','ADVANCED',0.8500,50,0.8500,0.8000,6,ARRAY['MEDIUM','HARD']::VARCHAR(16)[],12),
('01900000-0000-7000-8000-000000000213','01900000-0000-7000-8000-000000000113','01900000-0000-7000-8000-000000000002','Replication','Explain replica placement and replication factor.','INTERMEDIATE',0.8000,40,0.8000,0.7500,5,ARRAY['EASY','MEDIUM']::VARCHAR(16)[],13),
('01900000-0000-7000-8000-000000000214','01900000-0000-7000-8000-000000000114','01900000-0000-7000-8000-000000000002','In-Sync Replicas','Relate ISR membership to producer durability.','ADVANCED',0.8500,45,0.8500,0.8000,6,ARRAY['MEDIUM','HARD']::VARCHAR(16)[],14),
('01900000-0000-7000-8000-000000000215','01900000-0000-7000-8000-000000000115','01900000-0000-7000-8000-000000000002','Failure Recovery','Reason about leader failover and recovery behavior.','ADVANCED',0.8500,55,0.8500,0.8000,6,ARRAY['MEDIUM','HARD']::VARCHAR(16)[],15);

INSERT INTO core.learning_objective (id, skill_version_id, objective_code, description, display_order) VALUES
('01900000-0000-7000-8000-000000000301','01900000-0000-7000-8000-000000000201','BROKER_RESPONSIBILITY','Describe broker storage and request responsibilities.',1),
('01900000-0000-7000-8000-000000000302','01900000-0000-7000-8000-000000000202','TOPIC_SEMANTICS','Explain topic identity and retention-independent stream semantics.',1),
('01900000-0000-7000-8000-000000000303','01900000-0000-7000-8000-000000000203','PARTITION_ORDERING','State the ordering guarantee within a partition.',1),
('01900000-0000-7000-8000-000000000304','01900000-0000-7000-8000-000000000204','RECORD_ANATOMY','Identify the fields of a Kafka record.',1),
('01900000-0000-7000-8000-000000000305','01900000-0000-7000-8000-000000000205','SERIALIZER_COMPATIBILITY','Choose serializers compatible with producer and consumer contracts.',1),
('01900000-0000-7000-8000-000000000306','01900000-0000-7000-8000-000000000206','PARTITION_SELECTION','Predict partition selection for keyed records.',1),
('01900000-0000-7000-8000-000000000307','01900000-0000-7000-8000-000000000207','ACK_DURABILITY','Compare durability guarantees of producer acknowledgement modes.',1),
('01900000-0000-7000-8000-000000000308','01900000-0000-7000-8000-000000000208','IDEMPOTENT_DELIVERY','Explain how idempotent production prevents duplicate writes.',1),
('01900000-0000-7000-8000-000000000309','01900000-0000-7000-8000-000000000209','GROUP_ASSIGNMENT','Determine partition ownership within a consumer group.',1),
('01900000-0000-7000-8000-000000000310','01900000-0000-7000-8000-000000000210','OFFSET_POSITION','Interpret committed and current consumer positions.',1),
('01900000-0000-7000-8000-000000000311','01900000-0000-7000-8000-000000000211','COMMIT_TRADEOFFS','Select a commit strategy for stated processing guarantees.',1),
('01900000-0000-7000-8000-000000000312','01900000-0000-7000-8000-000000000212','REBALANCE_DIAGNOSIS','Identify rebalance triggers and mitigation options.',1),
('01900000-0000-7000-8000-000000000313','01900000-0000-7000-8000-000000000213','REPLICATION_FACTOR','Relate replication factor to fault tolerance.',1),
('01900000-0000-7000-8000-000000000314','01900000-0000-7000-8000-000000000214','ISR_DURABILITY','Explain the interaction of ISR and minimum in-sync replicas.',1),
('01900000-0000-7000-8000-000000000315','01900000-0000-7000-8000-000000000215','FAILOVER_SEQUENCE','Trace leader failure and replica promotion.',1);

INSERT INTO core.skill_prerequisite (curriculum_version_id, skill_id, prerequisite_skill_id) VALUES
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000102','01900000-0000-7000-8000-000000000101'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000103','01900000-0000-7000-8000-000000000102'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000104','01900000-0000-7000-8000-000000000102'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000105','01900000-0000-7000-8000-000000000104'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000106','01900000-0000-7000-8000-000000000103'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000106','01900000-0000-7000-8000-000000000105'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000107','01900000-0000-7000-8000-000000000101'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000108','01900000-0000-7000-8000-000000000107'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000109','01900000-0000-7000-8000-000000000103'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000110','01900000-0000-7000-8000-000000000109'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000111','01900000-0000-7000-8000-000000000110'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000112','01900000-0000-7000-8000-000000000109'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000113','01900000-0000-7000-8000-000000000103'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000114','01900000-0000-7000-8000-000000000113'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000115','01900000-0000-7000-8000-000000000114'),
('01900000-0000-7000-8000-000000000002','01900000-0000-7000-8000-000000000115','01900000-0000-7000-8000-000000000112');

UPDATE core.curriculum_version
SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
WHERE id = '01900000-0000-7000-8000-000000000002';
