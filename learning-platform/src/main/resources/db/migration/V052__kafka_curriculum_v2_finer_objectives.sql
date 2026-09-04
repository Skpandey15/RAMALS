-- H3: finer-grained objectives for the five Kafka skills that have real assessment content, so a
-- diagnosis can say "producer idempotence" instead of "producer acks, 71%".
--
-- Not a change to the existing (v1) curriculum version. core.learning_objective is scoped to a
-- curriculum_version via skill_version, and objectiveCoverage is recomputed fresh on every mastery
-- aggregation as coveredRequiredObjectives / requiredObjectives -- it is not a stored historical
-- value. Splitting a skill's single required objective into several, in place, would silently
-- change what "covered" means for every learner who already has evidence against the old scheme:
-- their objectiveCoverage (and possibly their MASTERED status) would drop the moment mastery next
-- recomputed, for a reason that has nothing to do with anything they did.
--
-- So this mints a new KAFKA curriculum_version ('v2') instead of mutating the existing one -- the
-- same "new capability, new version, old one stays exactly as it was" discipline this project
-- already holds assessment content and selectors to. v1's curriculum_version, skill_versions,
-- objectives and every mastery_snapshot recorded against them are untouched by this migration.
--
-- What moves onto the new curriculum version: all 15 skills (same thresholds, same difficulty,
-- same prerequisite graph -- copied forward unchanged) and the Kafka v2 DRAFT assessment bank
-- (V049's 35 items), re-pointed at it and re-tagged against the finer objectives. v2 has never been
-- published and carries no evidence, so re-tagging it is a safe, dev-time content correction, not a
-- change to anything a learner has ever seen. The five skills with no assessment content yet keep
-- their single objective, carried forward unchanged -- splitting an objective with no content behind
-- it to differentiate would be inventing structure, not describing real coverage.
--
-- v1 (5 items, PUBLISHED, immutable) is not touched, does not move to the new curriculum version,
-- and keeps its original single-objective tags on the original curriculum version. It is not
-- diagnosable at the finer grain this migration adds -- only the still-unpublished v2 bank is.

-- ---------------------------------------------------------------------------------------------
-- The new curriculum version, DRAFT until every skill has at least one required objective
-- (core.validate_curriculum_publication enforces this on the DRAFT -> PUBLISHED transition).
-- ---------------------------------------------------------------------------------------------

INSERT INTO core.curriculum_version (id, domain_id, version_code) VALUES
  ('01900000-0000-7000-8000-000000000004',
   '01900000-0000-7000-8000-000000000001', 'v2');

-- ---------------------------------------------------------------------------------------------
-- All 15 skills, carried forward with identical thresholds, difficulty and estimated minutes --
-- this migration changes objective granularity, not mastery gates. New ids derived from the old
-- ones the same way V049 derived logical_item_id from item_version_id: right(), not a substr()
-- position count, so the mapping is visible by inspection.
-- ---------------------------------------------------------------------------------------------

INSERT INTO core.skill_version (
  id, skill_id, curriculum_version_id, title, description, difficulty,
  target_proficiency, estimated_learning_minutes, mastery_threshold,
  confidence_threshold, required_evidence_count, required_difficulty_bands, display_order
)
SELECT ('01900000-0000-7000-8000-000000000B' || right(id::text, 2))::uuid,
       skill_id, '01900000-0000-7000-8000-000000000004', title, description, difficulty,
       target_proficiency, estimated_learning_minutes, mastery_threshold,
       confidence_threshold, required_evidence_count, required_difficulty_bands, display_order
FROM core.skill_version
WHERE curriculum_version_id = '01900000-0000-7000-8000-000000000002';

-- ---------------------------------------------------------------------------------------------
-- The prerequisite graph, identical edges, re-pointed at the new curriculum version.
-- ---------------------------------------------------------------------------------------------

INSERT INTO core.skill_prerequisite (curriculum_version_id, skill_id, prerequisite_skill_id)
SELECT '01900000-0000-7000-8000-000000000004', skill_id, prerequisite_skill_id
FROM core.skill_prerequisite
WHERE curriculum_version_id = '01900000-0000-7000-8000-000000000002';

-- ---------------------------------------------------------------------------------------------
-- The ten skills with no assessment content yet keep their single objective, unchanged -- carried
-- forward onto the corresponding new skill_version, same objective_code and description.
-- ---------------------------------------------------------------------------------------------

INSERT INTO core.learning_objective (id, skill_version_id, objective_code, description, display_order)
SELECT ('01900000-0000-7000-8000-000000000C' || right(lo.id::text, 2))::uuid,
       newsv.id, lo.objective_code, lo.description, lo.display_order
FROM core.learning_objective lo
JOIN core.skill_version oldsv ON oldsv.id = lo.skill_version_id
JOIN core.skill_version newsv
  ON newsv.skill_id = oldsv.skill_id
 AND newsv.curriculum_version_id = '01900000-0000-7000-8000-000000000004'
WHERE oldsv.curriculum_version_id = '01900000-0000-7000-8000-000000000002'
  AND oldsv.skill_id NOT IN (
    '01900000-0000-7000-8000-000000000101', -- KAFKA_BROKER
    '01900000-0000-7000-8000-000000000102', -- KAFKA_TOPIC
    '01900000-0000-7000-8000-000000000103', -- KAFKA_PARTITION
    '01900000-0000-7000-8000-000000000107', -- KAFKA_PRODUCER_ACKS
    '01900000-0000-7000-8000-000000000109'  -- KAFKA_CONSUMER_GROUPS
  );

-- ---------------------------------------------------------------------------------------------
-- The five skills with real content: each objective below is grounded in what V049's 35 items
-- actually test, not invented ahead of content. Three per skill, matching the real distribution of
-- what those seven items cover -- not padded to a uniform count, and not one-objective-per-item,
-- which would be over-fragmentation rather than diagnosis.
-- ---------------------------------------------------------------------------------------------

INSERT INTO core.learning_objective (id, skill_version_id, objective_code, description, display_order) VALUES
  -- KAFKA_BROKER (new skill_version ...0B01)
  ('01900000-0000-7000-8000-000000000d01','01900000-0000-7000-8000-000000000b01',
   'BROKER_STORAGE_MODEL','Explain what a broker persists to disk and how (append-only log segments).',1),
  ('01900000-0000-7000-8000-000000000d02','01900000-0000-7000-8000-000000000b01',
   'BROKER_CONTROLLER_ROLE','Explain the controller broker''s role in leader election and metadata propagation.',2),
  ('01900000-0000-7000-8000-000000000d03','01900000-0000-7000-8000-000000000b01',
   'BROKER_CLUSTER_OPERATIONS','Reason about broker-level behavior under scaling and failure -- adding brokers, replication-factor trade-offs, and durability during a broker outage.',3),

  -- KAFKA_TOPIC (new skill_version ...0B02)
  ('01900000-0000-7000-8000-000000000d04','01900000-0000-7000-8000-000000000b02',
   'TOPIC_RETENTION_AND_COMPACTION','Explain time-based retention and log compaction, including compaction''s interaction with tombstones and delete.retention.ms.',1),
  ('01900000-0000-7000-8000-000000000d05','01900000-0000-7000-8000-000000000b02',
   'TOPIC_ORDERING_SCOPE','State what ordering guarantee a topic does and does not provide, and how a partition-count change affects existing key routing.',2),
  ('01900000-0000-7000-8000-000000000d06','01900000-0000-7000-8000-000000000b02',
   'TOPIC_TRANSACTIONAL_ISOLATION','Explain how consumer isolation level affects visibility of transactionally written records.',3),

  -- KAFKA_PARTITION (new skill_version ...0B03)
  ('01900000-0000-7000-8000-000000000d07','01900000-0000-7000-8000-000000000b03',
   'PARTITION_ORDERING','State the ordering guarantee within a partition and predict key-to-partition routing under the default partitioner.',1),
  ('01900000-0000-7000-8000-000000000d08','01900000-0000-7000-8000-000000000b03',
   'PARTITION_PARALLELISM','Explain how partition count bounds a consumer group''s useful parallelism.',2),
  ('01900000-0000-7000-8000-000000000d09','01900000-0000-7000-8000-000000000b03',
   'PARTITION_AVAILABILITY_AND_SKEW','Reason about partition-level leader failover and the effect of key-traffic skew on a single partition''s load.',3),

  -- KAFKA_PRODUCER_ACKS (new skill_version ...0B07)
  ('01900000-0000-7000-8000-000000000d10','01900000-0000-7000-8000-000000000b07',
   'ACKS_SEMANTICS','State what each acks setting (0, 1, all) actually waits for before acknowledging a write.',1),
  ('01900000-0000-7000-8000-000000000d11','01900000-0000-7000-8000-000000000b07',
   'ACKS_DURABILITY_TRADEOFFS','Explain how acks interacts with min.insync.replicas to determine the actual durability guarantee.',2),
  ('01900000-0000-7000-8000-000000000d12','01900000-0000-7000-8000-000000000b07',
   'PRODUCER_IDEMPOTENCE','Explain how an idempotent producer prevents duplicate writes on retry after a leader failover.',3),

  -- KAFKA_CONSUMER_GROUPS (new skill_version ...0B09)
  ('01900000-0000-7000-8000-000000000d13','01900000-0000-7000-8000-000000000b09',
   'GROUP_PARTITION_ASSIGNMENT','State how partitions are assigned to consumers within a group, and what happens with more consumers than partitions.',1),
  ('01900000-0000-7000-8000-000000000d14','01900000-0000-7000-8000-000000000b09',
   'GROUP_ISOLATION','Explain that separate consumer groups reading the same topic each receive their own independent copy of every record.',2),
  ('01900000-0000-7000-8000-000000000d15','01900000-0000-7000-8000-000000000b09',
   'GROUP_REBALANCE_TRIGGERS','Diagnose what causes a consumer group to rebalance, including slow per-poll processing relative to max.poll.interval.ms.',3);

-- ---------------------------------------------------------------------------------------------
-- Publish. Every skill_version above has at least one required objective (ten carried forward,
-- five split into three), so core.validate_curriculum_publication's guard is satisfied.
-- ---------------------------------------------------------------------------------------------

UPDATE core.curriculum_version SET status = 'PUBLISHED'
 WHERE id = '01900000-0000-7000-8000-000000000004';

-- ---------------------------------------------------------------------------------------------
-- Re-point the Kafka v2 DRAFT assessment bank at the new curriculum version. It has never been
-- published and carries no evidence, so this is a content correction, not a change visible to any
-- learner -- v2 status stays DRAFT; that decision is unrelated to this migration and unchanged by
-- it. v1's assessment_version keeps its original curriculum_version_id, untouched.
-- ---------------------------------------------------------------------------------------------

UPDATE core.assessment_version SET curriculum_version_id = '01900000-0000-7000-8000-000000000004'
 WHERE id = '01900000-0000-7000-8000-000000000403';

-- ---------------------------------------------------------------------------------------------
-- Re-tag the 35 v2 items against the finer objectives, replacing the single-objective tags V049
-- gave them. Each mapping below was checked against that item's actual stem, not assigned by skill
-- alone -- e.g. KAFKA_BROKER's I1 item ("a broker goes down, do acks=all writes still succeed")
-- tests cluster-operational behavior under failure, not storage, so it is tagged to
-- BROKER_CLUSTER_OPERATIONS rather than BROKER_STORAGE_MODEL despite being about the same skill as
-- the storage item.
-- ---------------------------------------------------------------------------------------------

DELETE FROM core.assessment_item_objective
 WHERE item_version_id IN (
   SELECT id FROM core.assessment_item_version
    WHERE assessment_version_id = '01900000-0000-7000-8000-000000000403'
 );

INSERT INTO core.assessment_item_objective (item_version_id, objective_id)
SELECT iv.id,
       CASE iv.item_code
         WHEN 'KAFKA_V2_BROKER_MCQ_F'   THEN '01900000-0000-7000-8000-000000000d01' -- storage model
         WHEN 'KAFKA_V2_BROKER_MCQ_I1'  THEN '01900000-0000-7000-8000-000000000d03' -- cluster operations
         WHEN 'KAFKA_V2_BROKER_MCQ_I2'  THEN '01900000-0000-7000-8000-000000000d03' -- cluster operations
         WHEN 'KAFKA_V2_BROKER_MCQ_A1'  THEN '01900000-0000-7000-8000-000000000d02' -- controller role
         WHEN 'KAFKA_V2_BROKER_MCQ_A2'  THEN '01900000-0000-7000-8000-000000000d03' -- cluster operations
         WHEN 'KAFKA_V2_BROKER_FILL_F'  THEN '01900000-0000-7000-8000-000000000d01' -- storage model
         WHEN 'KAFKA_V2_BROKER_FILL_I'  THEN '01900000-0000-7000-8000-000000000d02' -- controller role

         WHEN 'KAFKA_V2_TOPIC_MCQ_F'    THEN '01900000-0000-7000-8000-000000000d04' -- retention/compaction
         WHEN 'KAFKA_V2_TOPIC_MCQ_I1'   THEN '01900000-0000-7000-8000-000000000d04' -- retention/compaction
         WHEN 'KAFKA_V2_TOPIC_MCQ_I2'   THEN '01900000-0000-7000-8000-000000000d05' -- ordering scope
         WHEN 'KAFKA_V2_TOPIC_MCQ_A1'   THEN '01900000-0000-7000-8000-000000000d04' -- retention/compaction
         WHEN 'KAFKA_V2_TOPIC_MCQ_A2'   THEN '01900000-0000-7000-8000-000000000d06' -- transactional isolation
         WHEN 'KAFKA_V2_TOPIC_FILL_F'   THEN '01900000-0000-7000-8000-000000000d05' -- ordering scope
         WHEN 'KAFKA_V2_TOPIC_FILL_I'   THEN '01900000-0000-7000-8000-000000000d04' -- retention/compaction

         WHEN 'KAFKA_V2_PARTITION_MCQ_F'   THEN '01900000-0000-7000-8000-000000000d08' -- parallelism
         WHEN 'KAFKA_V2_PARTITION_MCQ_I1'  THEN '01900000-0000-7000-8000-000000000d07' -- ordering
         WHEN 'KAFKA_V2_PARTITION_MCQ_I2'  THEN '01900000-0000-7000-8000-000000000d09' -- availability/skew
         WHEN 'KAFKA_V2_PARTITION_MCQ_A1'  THEN '01900000-0000-7000-8000-000000000d08' -- parallelism
         WHEN 'KAFKA_V2_PARTITION_MCQ_A2'  THEN '01900000-0000-7000-8000-000000000d09' -- availability/skew
         WHEN 'KAFKA_V2_PARTITION_FILL_F'  THEN '01900000-0000-7000-8000-000000000d07' -- ordering
         WHEN 'KAFKA_V2_PARTITION_FILL_I'  THEN '01900000-0000-7000-8000-000000000d08' -- parallelism

         WHEN 'KAFKA_V2_ACKS_MCQ_F'   THEN '01900000-0000-7000-8000-000000000d10' -- semantics
         WHEN 'KAFKA_V2_ACKS_MCQ_I1'  THEN '01900000-0000-7000-8000-000000000d10' -- semantics
         WHEN 'KAFKA_V2_ACKS_MCQ_I2'  THEN '01900000-0000-7000-8000-000000000d11' -- durability trade-offs
         WHEN 'KAFKA_V2_ACKS_MCQ_A1'  THEN '01900000-0000-7000-8000-000000000d11' -- durability trade-offs
         WHEN 'KAFKA_V2_ACKS_MCQ_A2'  THEN '01900000-0000-7000-8000-000000000d12' -- idempotence
         WHEN 'KAFKA_V2_ACKS_FILL_F'  THEN '01900000-0000-7000-8000-000000000d10' -- semantics
         WHEN 'KAFKA_V2_ACKS_FILL_I'  THEN '01900000-0000-7000-8000-000000000d10' -- semantics

         WHEN 'KAFKA_V2_CGROUP_MCQ_F'   THEN '01900000-0000-7000-8000-000000000d13' -- partition assignment
         WHEN 'KAFKA_V2_CGROUP_MCQ_I1'  THEN '01900000-0000-7000-8000-000000000d14' -- group isolation
         WHEN 'KAFKA_V2_CGROUP_MCQ_I2'  THEN '01900000-0000-7000-8000-000000000d15' -- rebalance triggers
         WHEN 'KAFKA_V2_CGROUP_MCQ_A1'  THEN '01900000-0000-7000-8000-000000000d15' -- rebalance triggers
         WHEN 'KAFKA_V2_CGROUP_MCQ_A2'  THEN '01900000-0000-7000-8000-000000000d13' -- partition assignment
         WHEN 'KAFKA_V2_CGROUP_FILL_F'  THEN '01900000-0000-7000-8000-000000000d13' -- partition assignment
         WHEN 'KAFKA_V2_CGROUP_FILL_I'  THEN '01900000-0000-7000-8000-000000000d15' -- rebalance triggers
       END::uuid
FROM core.assessment_item_version iv
WHERE iv.assessment_version_id = '01900000-0000-7000-8000-000000000403';
