package io.ramals.learningplatform.assessment.misconceptiongraph;

import io.ramals.learningplatform.assessment.MisconceptionTargetType;
import java.util.UUID;

/**
 * The resolved curriculum context of a {@link MisconceptionGraphView}'s requested node: which kind
 * it is, its own id and authored label, the objective it ultimately belongs to, and the skill /
 * curriculum version / domain that owns that objective (M2-ADR-033 §5). Immutable.
 *
 * <p>This is authored curriculum structure only. No learner state, no mastery, no evidence -- the
 * owning skill and curriculum version are here so a reader can locate the node and so the
 * prerequisite list and prerequisite-link semantics are unambiguous, not as a per-learner claim.
 *
 * @param label {@code objective_code} for a {@code LEARNING_OBJECTIVE}, otherwise the diagnostic
 *     node's authored {@code name}
 * @param objectiveId the objective this node is or belongs to (itself for a {@code
 *     LEARNING_OBJECTIVE}; the parent {@code CONCEPT}'s objective for a {@code SUB_CONCEPT})
 * @param owningSkillId the {@code core.skill} whose {@code skill_version} the objective hangs from
 * @param owningSkillCode that skill's stable code
 * @param curriculumVersionId the curriculum version the objective's skill version belongs to --
 *     the scope the co-located prerequisites and the prerequisite-link rule are read within
 * @param domainCode the learning domain that curriculum version belongs to
 */
public record GraphTargetView(
    MisconceptionTargetType kind,
    UUID id,
    String label,
    UUID objectiveId,
    UUID owningSkillId,
    String owningSkillCode,
    UUID curriculumVersionId,
    String domainCode) {
}
