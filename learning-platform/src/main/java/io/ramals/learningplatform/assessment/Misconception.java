package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * Granular diagnostic ontology foundation (M2-ADR-026): one {@code core.misconception} row -- a
 * named, specific, incorrect belief. Orthogonal to {@link DiagnosticNode}: never itself a node,
 * never counted toward objective coverage or mastery.
 *
 * @param targetObjectiveId set iff this misconception targets a {@code LearningObjective} directly
 *     (no finer node exists yet for it)
 * @param targetDiagnosticNodeId set iff this misconception targets a {@link DiagnosticNode}
 *     (a CONCEPT or SUB_CONCEPT -- distinguished by that node's own {@link DiagnosticNodeType}).
 *     Exactly one of the two target fields is ever set (the database's own exclusive-arc CHECK,
 *     {@code ck_misconception_target}, is what actually proves this, not this record).
 */
public record Misconception(
    UUID id,
    String name,
    String description,
    UUID targetObjectiveId,
    UUID targetDiagnosticNodeId) {
}
