/**
 * Controlled multi-agent orchestration (M2-T14).
 *
 * <p>The composition spine lives here rather than in the AI plane. LangGraph governs one agent's
 * execution flow; it does not hold a workflow milestone, because a milestone kept in an agent's
 * checkpoint is not observable to the deterministic services that own learner state.
 */
package io.ramals.learningplatform.orchestration;
