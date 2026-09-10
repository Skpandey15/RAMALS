/**
 * Misconception relationship graph foundation (M2-ADR-033 step 1).
 *
 * <p>Authored curriculum knowledge, <b>not</b> learner state and <b>not</b> AI state. This package
 * persists and deterministically validates two authored, non-authoritative edge kinds:
 *
 * <ul>
 *   <li>{@code MISCONCEPTION_RELATED} ({@code core.misconception_relationship}) -- an edge between
 *       two published misconceptions carrying a {@link
 *       io.ramals.learningplatform.assessment.misconceptiongraph.MisconceptionRelatedType} from a
 *       closed set; symmetric types are stored once under a canonical ordering, {@code SPECIALISES}
 *       is directed and acyclic;
 *   <li>{@code MISCONCEPTION_PREREQUISITE_LINK} ({@code core.misconception_prerequisite_link}) --
 *       an edge from a published misconception to a {@code core.skill} that is a curriculum
 *       prerequisite of the skill owning the misconception's target node.
 * </ul>
 *
 * <p>An edge never stores a learner id, mastery, confidence, probability, evidence observation,
 * posterior, ranking score, adaptive weight, LLM confidence, or recommendation outcome
 * (M2-ADR-033 §4). A misconception stays orthogonal to the {@code LearningObjective -> Concept ->
 * Sub-concept} tree -- no {@code parentMisconceptionId}, no {@code misconceptionLevel}, no third
 * nesting level (M2-ADR-026 §1/§3, M2-ADR-033 §2/§3).
 *
 * <p><b>Step 1 is representation only.</b> There is no graph traversal/query surface (Step 2), no
 * adaptive selection, no information gain, no {@code DIAGNOSTIC_SELECTION_V6}, and no AI graph
 * authoring. Nothing here is read by {@code DiagnosticService}, {@code DIAGNOSTIC_SELECTION_V1}-
 * {@code V5}, {@code ProbeRelationshipResolver}, or any H6/H7 read.
 */
package io.ramals.learningplatform.assessment.misconceptiongraph;
