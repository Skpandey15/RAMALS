-- The Kafka v2 diagnostic bank: enough scoreable content per skill to run several adaptive cycles
-- without repeating a question for the same learner.
--
-- v1 gave each of five skills exactly one item. A no-repeat guarantee against a one-item pool has
-- nothing to draw from on cycle two, so this version targets seven scoreable items per skill --
-- five SINGLE_CHOICE across the full difficulty range, two FILL_BLANK -- distributed so that a
-- learner who needs more evidence at a given band can usually get an unseen item there.
--
-- SHORT_ANSWER and USE_CASE are not authored here. Authoring content nobody can be shown yet is
-- authoring content nobody has reviewed against a real rubric contract, and M2-ADR-022 is what
-- settles that contract. They arrive with PR-C.
--
-- Every item is a genuinely new question, not a reworded copy of a v1 item, so each gets its own
-- logical identity in core.assessment_item_lineage -- there is no v1 item for any of these to be a
-- revision of.
--
-- v2 is authored and VERIFIED here, and deliberately left DRAFT. core.assessment_version.published_at
-- decides which version findPublishedDiagnostic("KAFKA") hands to every new attempt, ordered most
-- recent first -- so publishing v2 in this migration would silently replace what every learner
-- receives the moment it runs, through a selector that has no idea a packet policy exists. V045's
-- selector fills a target size from whatever the pool offers with no type quota and no awareness of
-- the 5 SINGLE_CHOICE + 2 FILL_BLANK transitional packet this content was authored for; against a
-- pool this size it reliably pulls more than seven items and an arbitrary type mix. That is PR-B's
-- selector to build, not a side effect of this migration authoring content.
--
-- What IS proved here, and real rather than a throwaway probe: both publication guards accept
-- content that satisfies them. Verified directly against PostgreSQL as part of this change --
-- transitioning this same DRAFT row to PUBLISHED succeeds once every item is VERIFIED_CONTENT
-- (V017) and lineaged (V048), which is exactly the state the INSERTs below leave it in. Publishing
-- v2 for real is one statement, left for whichever PR is ready to serve it correctly.

INSERT INTO core.assessment_version (id, assessment_id, curriculum_version_id, version_code) VALUES
  ('01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000401',
   '01900000-0000-7000-8000-000000000002', 'v2');

-- ---------------------------------------------------------------------------------------------
-- KAFKA_BROKER  (skill 0101, objective 0301 BROKER_RESPONSIBILITY)  -- items 1-7, display_order 1-7
-- ---------------------------------------------------------------------------------------------

INSERT INTO core.assessment_item_version (
  id, assessment_version_id, skill_id, item_code, item_type, stem,
  options_jsonb, answer_key_jsonb, difficulty, display_order,
  trust_state, verified_by, verified_at
) VALUES
  ('01900000-0000-7000-8000-000000000601','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000101','KAFKA_V2_BROKER_MCQ_F','SINGLE_CHOICE',
   'What does a Kafka broker persist to local disk for each partition it hosts?',
   '[{"id":"A","text":"A single flat file per topic"},{"id":"B","text":"An ordered, append-only log segment"},{"id":"C","text":"A copy of the cluster metadata quorum state"},{"id":"D","text":"An in-memory hash table only, with no disk copy"}]',
   '{"correct":["B"]}','FOUNDATIONAL',1,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000602','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000101','KAFKA_V2_BROKER_MCQ_I1','SINGLE_CHOICE',
   'A cluster has 3 brokers. A topic has replication factor 3 and min.insync.replicas=2. One broker goes down. What happens to producer writes sent with acks=all?',
   '[{"id":"A","text":"All writes fail immediately"},{"id":"B","text":"Writes succeed as long as 2 in-sync replicas remain"},{"id":"C","text":"Writes are silently downgraded to acks=1"},{"id":"D","text":"The topic becomes read-only until the broker returns"}]',
   '{"correct":["B"]}','INTERMEDIATE',2,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000603','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000101','KAFKA_V2_BROKER_MCQ_I2','SINGLE_CHOICE',
   'Why does adding more brokers to a running cluster not, by itself, rebalance existing topic-partitions onto them?',
   '[{"id":"A","text":"Broker addition always triggers immediate automatic rebalancing"},{"id":"B","text":"Partition-to-broker assignment is fixed at creation time and needs an explicit reassignment"},{"id":"C","text":"Only newly created topics can ever use newly added brokers"},{"id":"D","text":"Broker count never affects where partitions are placed"}]',
   '{"correct":["B"]}','INTERMEDIATE',3,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000604','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000101','KAFKA_V2_BROKER_MCQ_A1','SINGLE_CHOICE',
   'A cluster controller broker experiences a long garbage-collection pause. During the pause, consumers report stale partition-leadership metadata for several partitions. What is the most direct cause?',
   '[{"id":"A","text":"The controller could not propagate a new leader election while paused"},{"id":"B","text":"Consumers cache leadership metadata permanently by design and never refresh it"},{"id":"C","text":"The internal offsets topic became unavailable"},{"id":"D","text":"Consumers elected a leader among themselves in place of the controller"}]',
   '{"correct":["A"]}','ADVANCED',4,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000605','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000101','KAFKA_V2_BROKER_MCQ_A2','SINGLE_CHOICE',
   'A topic sustains 500 MB/s of produced data with replication factor 3, and disk I/O rather than CPU is the bottleneck on every broker. Which single change most directly reduces the disk write I/O each broker must sustain per unit of produced data, and what does it trade away?',
   '[{"id":"A","text":"Increase the number of partitions on the same brokers; this spreads load without reducing total bytes each broker writes"},{"id":"B","text":"Lower the replication factor, which reduces the number of replica writes per broker at the cost of durability"},{"id":"C","text":"Increase consumer fetch size; this affects reads, not the write path"},{"id":"D","text":"Increase producer batch size alone, which reduces request count but not total bytes written to disk"}]',
   '{"correct":["B"]}','ADVANCED',5,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000606','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000101','KAFKA_V2_BROKER_FILL_F','FILL_BLANK',
   'A Kafka broker persists each partition as an ordered, append-only ______.',
   '[]','{"accepted":["log","commit log"]}','FOUNDATIONAL',6,
   'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000607','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000101','KAFKA_V2_BROKER_FILL_I','FILL_BLANK',
   'The broker responsible for partition leader election and propagating metadata changes to the rest of the cluster is called the ______.',
   '[]','{"accepted":["controller","controller broker"]}','INTERMEDIATE',7,
   'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP);

-- ---------------------------------------------------------------------------------------------
-- KAFKA_TOPIC  (skill 0102, objective 0302 TOPIC_SEMANTICS)  -- items 8-14
-- ---------------------------------------------------------------------------------------------

INSERT INTO core.assessment_item_version (
  id, assessment_version_id, skill_id, item_code, item_type, stem,
  options_jsonb, answer_key_jsonb, difficulty, display_order,
  trust_state, verified_by, verified_at
) VALUES
  ('01900000-0000-7000-8000-000000000608','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000102','KAFKA_V2_TOPIC_MCQ_F','SINGLE_CHOICE',
   'A topic has retention.ms set to 7 days. What happens to a record older than 7 days?',
   '[{"id":"A","text":"All of the topic partitions are deleted after 7 days"},{"id":"B","text":"The record is discarded once it is older than 7 days, whether or not it has been consumed"},{"id":"C","text":"The topic refuses new writes after 7 days"},{"id":"D","text":"The record is automatically compacted to the latest value for its key"}]',
   '{"correct":["B"]}','FOUNDATIONAL',8,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000609','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000102','KAFKA_V2_TOPIC_MCQ_I1','SINGLE_CHOICE',
   'A topic is configured with cleanup.policy=compact. What guarantee does log compaction provide?',
   '[{"id":"A","text":"Every record ever written is retained forever, unmodified"},{"id":"B","text":"At least the latest record for each key is retained"},{"id":"C","text":"Records are deleted after a fixed time regardless of key"},{"id":"D","text":"Compaction removes duplicate topics from the cluster"}]',
   '{"correct":["B"]}','INTERMEDIATE',9,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000610','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000102','KAFKA_V2_TOPIC_MCQ_I2','SINGLE_CHOICE',
   'Why can increasing a topic partition count after creation break key-based ordering guarantees for keys that were already in use?',
   '[{"id":"A","text":"It cannot; partition count changes are always safe for existing keys"},{"id":"B","text":"The default partitioner can route a given key to a different partition than before, because its mapping depends on the current partition count"},{"id":"C","text":"Kafka automatically reassigns all historical records to preserve ordering"},{"id":"D","text":"Partition count changes require deleting and recreating the topic"}]',
   '{"correct":["B"]}','INTERMEDIATE',10,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000611','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000102','KAFKA_V2_TOPIC_MCQ_A1','SINGLE_CHOICE',
   'A topic set to cleanup.policy=compact,delete backs a changelog for stateful stream processing. Under load, a consumer occasionally reads an old value for a key even though a newer tombstone for that key was written earlier. What is the most likely explanation?',
   '[{"id":"A","text":"This cannot happen; compaction is strictly ordered for every consumer"},{"id":"B","text":"The tombstone was physically removed once delete.retention.ms elapsed, before that consumer had read it"},{"id":"C","text":"Consumers always see every key strictly in write order regardless of compaction"},{"id":"D","text":"Log compaction guarantees a tombstone is never removed"}]',
   '{"correct":["B"]}','ADVANCED',11,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000612','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000102','KAFKA_V2_TOPIC_MCQ_A2','SINGLE_CHOICE',
   'A design must guarantee that consumers never observe a partial multi-record transaction written by a transactional producer. Which configuration is essential to that guarantee?',
   '[{"id":"A","text":"Setting the producer to acks=0"},{"id":"B","text":"Setting the consumer isolation.level to read_committed"},{"id":"C","text":"Disabling log compaction on the topic"},{"id":"D","text":"Increasing the number of partitions on the topic"}]',
   '{"correct":["B"]}','ADVANCED',12,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000613','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000102','KAFKA_V2_TOPIC_FILL_F','FILL_BLANK',
   'Kafka guarantees ordering of records within a ______, not across an entire topic.',
   '[]','{"accepted":["partition"]}','FOUNDATIONAL',13,
   'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000614','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000102','KAFKA_V2_TOPIC_FILL_I','FILL_BLANK',
   'A cleanup.policy of ______ retains only the latest record for each key rather than deleting records by age.',
   '[]','{"accepted":["compact","compaction"]}','INTERMEDIATE',14,
   'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP);

-- ---------------------------------------------------------------------------------------------
-- KAFKA_PARTITION  (skill 0103, objective 0303 PARTITION_ORDERING)  -- items 15-21
-- ---------------------------------------------------------------------------------------------

INSERT INTO core.assessment_item_version (
  id, assessment_version_id, skill_id, item_code, item_type, stem,
  options_jsonb, answer_key_jsonb, difficulty, display_order,
  trust_state, verified_by, verified_at
) VALUES
  ('01900000-0000-7000-8000-000000000615','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000103','KAFKA_V2_PARTITION_MCQ_F','SINGLE_CHOICE',
   'What determines the maximum number of consumers within a single consumer group that can actively read from a topic in parallel?',
   '[{"id":"A","text":"The number of brokers in the cluster"},{"id":"B","text":"The number of partitions in the topic"},{"id":"C","text":"The replication factor of the topic"},{"id":"D","text":"The number of nodes in the metadata quorum"}]',
   '{"correct":["B"]}','FOUNDATIONAL',15,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000616','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000103','KAFKA_V2_PARTITION_MCQ_I1','SINGLE_CHOICE',
   'A producer sends every record for a given key to a topic using the default partitioner, with a stable partition count. What can you conclude about where those records land?',
   '[{"id":"A","text":"They are spread randomly across all partitions"},{"id":"B","text":"They are all routed to the same partition, which preserves their order relative to each other"},{"id":"C","text":"They are routed round-robin regardless of key"},{"id":"D","text":"They always go to partition 0"}]',
   '{"correct":["B"]}','INTERMEDIATE',16,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000617','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000103','KAFKA_V2_PARTITION_MCQ_I2','SINGLE_CHOICE',
   'A topic has 6 partitions, 3 hosted with their leader on broker A and 3 on broker B, replication factor 2. Broker A fails. What happens to consumption of the 3 partitions it led?',
   '[{"id":"A","text":"Those partitions become permanently unavailable"},{"id":"B","text":"An in-sync replica on a remaining broker is elected leader for each, and consumption continues"},{"id":"C","text":"Consumers must be manually reassigned to broker B before reads resume"},{"id":"D","text":"The whole topic stops accepting reads until broker A returns"}]',
   '{"correct":["B"]}','INTERMEDIATE',17,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000618','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000103','KAFKA_V2_PARTITION_MCQ_A1','SINGLE_CHOICE',
   'A consumer group has 6 consumers reading a topic with 4 partitions. Two consumers are permanently idle. What causes this, and what change lets all six process records?',
   '[{"id":"A","text":"This is a bug; restarting the idle consumers resolves it"},{"id":"B","text":"A single consumer group assigns each partition to at most one consumer at a time, so with more consumers than partitions the extras stay idle; increasing the partition count to at least 6 lets all six be assigned work"},{"id":"C","text":"acks is misconfigured on the producer; setting acks=all fixes it"},{"id":"D","text":"retention.ms is too short; increasing it fixes the idle consumers"}]',
   '{"correct":["B"]}','ADVANCED',18,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000619','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000103','KAFKA_V2_PARTITION_MCQ_A2','SINGLE_CHOICE',
   'A topic is partitioned by customer_id to preserve per-customer ordering. Traffic is skewed: one customer accounts for 40 percent of volume. What is the direct consequence for the partition holding that key, and why does adding more partitions alone not fix it?',
   '[{"id":"A","text":"That partition becomes a throughput hot spot; the default hashing partitioner only changes assignment for keys as the partition count changes, so an existing hot key can still land on an overloaded partition and the skew persists for it"},{"id":"B","text":"Nothing changes; Kafka automatically spreads a single key across partitions to balance load"},{"id":"C","text":"The skew resolves itself automatically once enough partitions exist"},{"id":"D","text":"Skew only affects consumers, never partition-level producer throughput"}]',
   '{"correct":["A"]}','ADVANCED',19,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000620','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000103','KAFKA_V2_PARTITION_FILL_F','FILL_BLANK',
   'Within a single Kafka ______, record order is guaranteed; across several, it is not.',
   '[]','{"accepted":["partition"]}','FOUNDATIONAL',20,
   'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000621','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000103','KAFKA_V2_PARTITION_FILL_I','FILL_BLANK',
   'The maximum useful parallelism of a single consumer group reading one topic is bounded by that topic number of ______.',
   '[]','{"accepted":["partitions","partition"]}','INTERMEDIATE',21,
   'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP);

-- ---------------------------------------------------------------------------------------------
-- KAFKA_PRODUCER_ACKS  (skill 0107, objective 0307 ACK_DURABILITY)  -- items 22-28
-- Requires band coverage MEDIUM+HARD, and had no ADVANCED item anywhere in the bank before this.
-- ---------------------------------------------------------------------------------------------

INSERT INTO core.assessment_item_version (
  id, assessment_version_id, skill_id, item_code, item_type, stem,
  options_jsonb, answer_key_jsonb, difficulty, display_order,
  trust_state, verified_by, verified_at
) VALUES
  ('01900000-0000-7000-8000-000000000622','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000107','KAFKA_V2_ACKS_MCQ_F','SINGLE_CHOICE',
   'Which acks setting means the producer does not wait for any broker acknowledgment before treating a write as successful?',
   '[{"id":"A","text":"acks=all"},{"id":"B","text":"acks=1"},{"id":"C","text":"acks=0"},{"id":"D","text":"acks=leader"}]',
   '{"correct":["C"]}','FOUNDATIONAL',22,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000623','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000107','KAFKA_V2_ACKS_MCQ_I1','SINGLE_CHOICE',
   'With acks=1, a producer receives a successful acknowledgment for a write. Under what circumstance can that record still be lost?',
   '[{"id":"A","text":"It cannot be lost once it has been acknowledged"},{"id":"B","text":"If the partition leader fails before the record is replicated to any follower"},{"id":"C","text":"If a consumer commits its offset too early"},{"id":"D","text":"If the topic uses log compaction"}]',
   '{"correct":["B"]}','INTERMEDIATE',23,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000624','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000107','KAFKA_V2_ACKS_MCQ_I2','SINGLE_CHOICE',
   'What does setting acks=all actually wait for?',
   '[{"id":"A","text":"Every replica in the cluster, whether in-sync or not"},{"id":"B","text":"Acknowledgment from the replicas currently in the in-sync replica set, as bounded by min.insync.replicas"},{"id":"C","text":"Only the partition leader"},{"id":"D","text":"A quorum vote among consumers"}]',
   '{"correct":["B"]}','INTERMEDIATE',24,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000625','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000107','KAFKA_V2_ACKS_MCQ_A1','SINGLE_CHOICE',
   'A team sets acks=all and min.insync.replicas=1 on a replication-factor-3 topic, believing this gives strong durability. During a rolling restart one broker briefly goes down. What durability gap does min.insync.replicas=1 leave open despite acks=all?',
   '[{"id":"A","text":"None; acks=all alone guarantees full durability regardless of min.insync.replicas"},{"id":"B","text":"With min.insync.replicas=1, acks=all is satisfied once a single in-sync replica acknowledges, which could be only the leader; a leader failure right after that ack can still lose the record if no follower had caught up"},{"id":"C","text":"min.insync.replicas only affects consumers, never producers"},{"id":"D","text":"acks=all ignores min.insync.replicas entirely"}]',
   '{"correct":["B"]}','ADVANCED',25,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000626','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000107','KAFKA_V2_ACKS_MCQ_A2','SINGLE_CHOICE',
   'A producer configured with acks=all and enable.idempotence=true has a leader failover mid-request due to a network partition, and retries the same batch once a new leader is elected. What prevents this retry from writing a duplicate record?',
   '[{"id":"A","text":"There is no such guarantee; duplicates are expected and must be removed downstream"},{"id":"B","text":"The idempotent producer per-partition sequence numbers let the new leader detect and discard a retried duplicate batch"},{"id":"C","text":"acks=all alone deduplicates regardless of idempotence settings"},{"id":"D","text":"Consumers silently filter duplicate records by content hash"}]',
   '{"correct":["B"]}','ADVANCED',26,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000627','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000107','KAFKA_V2_ACKS_FILL_F','FILL_BLANK',
   'Setting acks=______ gives the strongest producer durability guarantee, waiting on the in-sync replica set.',
   '[]','{"accepted":["all","-1"]}','FOUNDATIONAL',27,
   'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000628','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000107','KAFKA_V2_ACKS_FILL_I','FILL_BLANK',
   'With acks=1, a record is acknowledged once only the partition ______ has written it, before any follower replicates it.',
   '[]','{"accepted":["leader"]}','INTERMEDIATE',28,
   'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP);

-- ---------------------------------------------------------------------------------------------
-- KAFKA_CONSUMER_GROUPS  (skill 0109, objective 0309 GROUP_ASSIGNMENT)  -- items 29-35
-- ---------------------------------------------------------------------------------------------

INSERT INTO core.assessment_item_version (
  id, assessment_version_id, skill_id, item_code, item_type, stem,
  options_jsonb, answer_key_jsonb, difficulty, display_order,
  trust_state, verified_by, verified_at
) VALUES
  ('01900000-0000-7000-8000-000000000629','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000109','KAFKA_V2_CGROUP_MCQ_F','SINGLE_CHOICE',
   'Within a single consumer group, how many consumers may be actively assigned to consume from one partition at a time?',
   '[{"id":"A","text":"As many as are members of the group"},{"id":"B","text":"Exactly one"},{"id":"C","text":"Exactly two, for redundancy"},{"id":"D","text":"Zero; partitions are read directly by brokers"}]',
   '{"correct":["B"]}','FOUNDATIONAL',29,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000630','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000109','KAFKA_V2_CGROUP_MCQ_I1','SINGLE_CHOICE',
   'Two separate consumer groups, A and B, both subscribe to the same topic. What happens to the records?',
   '[{"id":"A","text":"Only group A receives them, since it subscribed first"},{"id":"B","text":"Each group independently receives and tracks its own copy of every record; the groups do not share consumption"},{"id":"C","text":"The groups must coordinate to split records between them"},{"id":"D","text":"The second group to subscribe is rejected"}]',
   '{"correct":["B"]}','INTERMEDIATE',30,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000631','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000109','KAFKA_V2_CGROUP_MCQ_I2','SINGLE_CHOICE',
   'A consumer in a group crashes without a clean shutdown. What triggers the group to notice and reassign its partitions?',
   '[{"id":"A","text":"Nothing; the partitions become permanently unassigned"},{"id":"B","text":"The group coordinator detects a missed heartbeat or session timeout and triggers a rebalance"},{"id":"C","text":"The producer detects the failure and reroutes writes"},{"id":"D","text":"The metadata quorum immediately reassigns the partitions to a random broker"}]',
   '{"correct":["B"]}','INTERMEDIATE',31,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000632','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000109','KAFKA_V2_CGROUP_MCQ_A1','SINGLE_CHOICE',
   'A consumer group rebalances every few minutes in production even though no consumer is crashing or being added. Processing a batch of records consistently takes close to the group max.poll.interval.ms. What is the most likely cause, and the most direct fix?',
   '[{"id":"A","text":"Network problems between brokers; add more brokers"},{"id":"B","text":"The consumer exceeds max.poll.interval.ms between poll calls because processing is too slow, so the coordinator treats it as dead and rebalances; increase max.poll.interval.ms or reduce per-poll work, for example with a smaller max.poll.records"},{"id":"C","text":"The topic has too few partitions; add partitions"},{"id":"D","text":"The consumer group has too many members; remove consumers"}]',
   '{"correct":["B"]}','ADVANCED',32,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000633','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000109','KAFKA_V2_CGROUP_MCQ_A2','SINGLE_CHOICE',
   'A team grows a consumer group from 4 to 10 consumers to raise throughput, but overall throughput does not improve. The topic has 4 partitions. What actually limits throughput here, and what is the downside of the extra consumers?',
   '[{"id":"A","text":"Throughput is limited by broker CPU, and the extra consumers cause no harm"},{"id":"B","text":"With only 4 partitions, at most 4 consumers can ever be assigned work; the other 6 sit permanently idle and add rebalance coordination overhead"},{"id":"C","text":"Adding consumers always increases throughput linearly, regardless of partition count"},{"id":"D","text":"The consumer group automatically creates more partitions to accommodate new consumers"}]',
   '{"correct":["B"]}','ADVANCED',33,'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000634','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000109','KAFKA_V2_CGROUP_FILL_F','FILL_BLANK',
   'A partition is assigned to exactly one ______ within a single consumer group at any time.',
   '[]','{"accepted":["consumer"]}','FOUNDATIONAL',34,
   'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP),

  ('01900000-0000-7000-8000-000000000635','01900000-0000-7000-8000-000000000403',
   '01900000-0000-7000-8000-000000000109','KAFKA_V2_CGROUP_FILL_I','FILL_BLANK',
   'When a poll loop exceeds max.poll.interval.ms, the group coordinator treats that consumer as dead and triggers a ______.',
   '[]','{"accepted":["rebalance"]}','INTERMEDIATE',35,
   'VERIFIED_CONTENT','kafka-v2-curriculum-authoring',CURRENT_TIMESTAMP);

-- ---------------------------------------------------------------------------------------------
-- Logical identity: every one of the 35 items above is a new question, so each gets its own,
-- freshly minted logical_item_id. None of these is a revision of a v1 item -- v1's five items keep
-- the logical identities V048 backfilled for them (...-0501 through ...-0505).
-- ---------------------------------------------------------------------------------------------

-- right(), not a substr() position count: the id's own last two hex characters (01 through 23 for
-- items 601-623) are reused verbatim as the logical id's last two characters, so the mapping from
-- item_version_id to logical_item_id is visible by inspection rather than requiring the offset
-- arithmetic to be trusted.
INSERT INTO core.assessment_item_lineage (item_version_id, logical_item_id)
SELECT id, ('01900000-0000-7000-8000-0000000007' || right(id::text, 2))::uuid
FROM core.assessment_item_version
WHERE assessment_version_id = '01900000-0000-7000-8000-000000000403';

-- ---------------------------------------------------------------------------------------------
-- Objective coverage: every item of a skill tagged against that skill's sole required objective,
-- the same objective V046 tagged the corresponding v1 item against. No new objectives are
-- introduced by this migration -- that is a curriculum decision, not a content-authoring one.
-- ---------------------------------------------------------------------------------------------

INSERT INTO core.assessment_item_objective (item_version_id, objective_id)
SELECT iv.id,
       CASE iv.skill_id
         WHEN '01900000-0000-7000-8000-000000000101' THEN '01900000-0000-7000-8000-000000000301' -- BROKER_RESPONSIBILITY
         WHEN '01900000-0000-7000-8000-000000000102' THEN '01900000-0000-7000-8000-000000000302' -- TOPIC_SEMANTICS
         WHEN '01900000-0000-7000-8000-000000000103' THEN '01900000-0000-7000-8000-000000000303' -- PARTITION_ORDERING
         WHEN '01900000-0000-7000-8000-000000000107' THEN '01900000-0000-7000-8000-000000000307' -- ACK_DURABILITY
         WHEN '01900000-0000-7000-8000-000000000109' THEN '01900000-0000-7000-8000-000000000309' -- GROUP_ASSIGNMENT
       END::uuid
FROM core.assessment_item_version iv
WHERE iv.assessment_version_id = '01900000-0000-7000-8000-000000000403';

-- v2 stays DRAFT. See the header comment: publishing it is a selector decision, made once a
-- selector exists that can honour the packet policy this content was authored for. What this
-- migration leaves behind is content that WOULD pass publication the moment that selector lands --
-- every item is VERIFIED_CONTENT and every item has a lineage row -- not a partial bank waiting on
-- more authoring.
