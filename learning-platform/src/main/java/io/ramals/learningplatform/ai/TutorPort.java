package io.ramals.learningplatform.ai;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;

/**
 * The platform's outbound view of the AI execution plane.
 *
 * <p>A port rather than a client class so the deterministic core depends on an interface it owns,
 * not on a transport. It also makes the AI-down tests honest: they substitute a failing port and
 * exercise the real service, instead of asserting against a mock of the thing under test.
 *
 * <p>One method, request/response. M1-ADR-004 decided the Tutor does not stream, so there is no
 * token channel to model here — and the absence of one is what keeps the deadline, cancellation and
 * circuit-breaking story simple.
 */
public interface TutorPort {

  /**
   * Requests a tutor proposal.
   *
   * @param deadlineMillis the caller's remaining budget, absolute in effect: the adapter must not
   *     begin work it cannot finish within it (M1-ADR-001).
   * @throws AiUnavailableException when the call was refused or failed. Never a checked exception:
   *     callers must be free to let tutoring degrade without a catch block on every path.
   */
  AiProposalEnvelope requestTutorResponse(AiRequestEnvelope request, long deadlineMillis);
}
