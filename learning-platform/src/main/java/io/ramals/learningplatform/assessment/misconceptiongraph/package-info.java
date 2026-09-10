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
 * <p><b>Step 1 (persistence + domain model + authored validation)</b> is the write side:
 * {@link io.ramals.learningplatform.assessment.misconceptiongraph.MisconceptionRelationshipService}
 * / {@code Validator} / {@code Repository} author and publish edges deterministically in Java.
 *
 * <p><b>Step 2 (M2-ADR-033 §5) is a bounded read-only query surface</b>:
 * {@link io.ramals.learningplatform.assessment.misconceptiongraph.MisconceptionGraphQueryService}
 * composes a deterministic {@link
 * io.ramals.learningplatform.assessment.misconceptiongraph.MisconceptionGraphView} for one
 * curriculum node -- its prerequisites and its {@code PUBLISHED} misconception knowledge -- in a
 * fixed number of bounded queries, {@code @Transactional(readOnly = true)}, exposing only authored
 * knowledge. It takes no learner input and returns nothing per-learner.
 *
 * <p>Still <b>not</b> in this package: adaptive selection, information gain, a posterior, {@code
 * DIAGNOSTIC_SELECTION_V6}, AI graph authoring, a learner-scoped overlay, graph traversal
 * ({@code findAllPaths} / {@code shortestPath} / ...), or any runtime wiring. Nothing here is read
 * by {@code DiagnosticService}, {@code DIAGNOSTIC_SELECTION_V1}-{@code V5}, {@code
 * ProbeRelationshipResolver}, or any H6/H7 service, and nothing here reads them.
 */
package io.ramals.learningplatform.assessment.misconceptiongraph;
