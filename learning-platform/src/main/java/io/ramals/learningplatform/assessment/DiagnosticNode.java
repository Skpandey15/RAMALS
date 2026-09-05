package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * Granular diagnostic ontology foundation (M2-ADR-026): one {@code core.diagnostic_node} row -- a
 * content-driven, optional diagnostic refinement of one {@code core.learning_objective}. Never a
 * {@code LearningObjective} itself; never read by objectiveCoverage or mastery computation.
 *
 * @param objectiveId set iff {@code nodeType == CONCEPT} -- the one objective this concept refines
 * @param parentNodeId set iff {@code nodeType == SUB_CONCEPT} -- the one concept this sub-concept
 *     refines; a sub-concept's own objective is derived through its parent, never duplicated here
 */
public record DiagnosticNode(
    UUID id,
    UUID objectiveId,
    UUID parentNodeId,
    DiagnosticNodeType nodeType,
    String name,
    String description,
    int displayOrder) {
}
