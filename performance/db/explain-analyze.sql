-- EXPLAIN ANALYZE for the RAMALS MVP-0 hot-path queries, captured before release.
-- Run as the runtime role against a populated dataset. Placeholder learner id
-- 00000000-0000-0000-0000-000000000001 shows plan/index usage even with no matching rows;
-- for representative timings, load a seeded learner with mastery/evidence/recommendation history.
--
-- Seed identifiers (KAFKA v1): curriculum_version 01900000-0000-7000-8000-000000000002,
-- KAFKA_BROKER skill 01900000-0000-7000-8000-000000000101,
-- assessment_version 01900000-0000-7000-8000-000000000402.

\set learner '00000000-0000-0000-0000-000000000001'
\set cv '01900000-0000-7000-8000-000000000002'
\set skill '01900000-0000-7000-8000-000000000101'
\set av '01900000-0000-7000-8000-000000000402'

\echo === Q1: latest mastery snapshot (MasteryRepository.findLatestSnapshot) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, aggregate_version, mastery_score, mastery_status
FROM ledger.mastery_snapshot
WHERE learner_id = :'learner' AND skill_id = :'skill' AND curriculum_version_id = :'cv'
ORDER BY aggregate_version DESC
LIMIT 1;

\echo === Q2: mastery map, latest per skill (MasteryRepository.latestMasteryMap) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT DISTINCT ON (ms.skill_id) s.stable_code, ms.mastery_score, ms.evidence_confidence,
       ms.mastery_status, ms.aggregate_version
FROM ledger.mastery_snapshot ms
JOIN core.skill s ON s.id = ms.skill_id
WHERE ms.learner_id = :'learner' AND ms.curriculum_version_id = :'cv'
ORDER BY ms.skill_id, ms.aggregate_version DESC;

\echo === Q3: evidence for recomputation (EvidenceRepository.findByLearnerAndSkill) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, evidence_type, normalized_score, items_answered, occurred_at
FROM ledger.evidence
WHERE learner_id = :'learner' AND skill_id = :'skill'
ORDER BY occurred_at, id;

\echo === Q4: curriculum skill graph load (CurriculumRepository skills query) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT sv.skill_id, s.stable_code, sv.title, sv.display_order
FROM core.skill_version sv
JOIN core.skill s ON s.id = sv.skill_id
WHERE sv.curriculum_version_id = :'cv'
ORDER BY sv.display_order, s.stable_code;

\echo === Q5: active attempt lookup, one-active partial index (AssessmentRepository.findActiveAttempt) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, status, idempotency_key
FROM core.assessment_attempt
WHERE learner_id = :'learner' AND assessment_version_id = :'av' AND status = 'IN_PROGRESS';

\echo === Q6: current recommendations per skill (RecommendationRepository.findCurrentByLearner) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT DISTINCT ON (lr.skill_id) lr.id, s.stable_code, lr.recommended_action, lr.created_at
FROM core.learning_recommendation lr
JOIN core.skill s ON s.id = lr.skill_id
WHERE lr.learner_id = :'learner'
ORDER BY lr.skill_id, lr.created_at DESC, lr.id DESC;

\echo === Q7: latest progression statuses (ProgressionRepository.latestStatuses) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT DISTINCT ON (skill_id) skill_id, mastery_status
FROM ledger.mastery_snapshot
WHERE learner_id = :'learner' AND curriculum_version_id = :'cv'
ORDER BY skill_id, aggregate_version DESC;

\echo === Q8: open learning session (LearningSessionRepository.findOpenSession) ===
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, status, version
FROM core.learning_session
WHERE learner_id = :'learner' AND curriculum_version_id = :'cv'
  AND status IN ('ACTIVE', 'PAUSED');
