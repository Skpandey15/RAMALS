package io.ramals.learningplatform.orchestration;

import io.ramals.learningplatform.diagnosticassessment.DiagnosticAssessmentService;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort.RecordedDecision;
import io.ramals.learningplatform.grounding.ProposalType;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the M2-T09 diagnostic path as one composition step.
 *
 * <p>The learner's subject is resolved here and passed down, because the diagnostic service accepts
 * only an authenticated subject and derives the learner itself. Handing it a learner id would open
 * exactly the cross-learner path that design closes -- so the workflow resolves the subject from its
 * own authoritative run row and lets the service re-derive everything else.
 */
@Component
public class DiagnosticAgentStep implements WorkflowAgentStep.Diagnostic {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticAgentStep.class);

  private final DiagnosticAssessmentService diagnostics;
  private final LearnerRepository learners;
  private final ProposalGateDecisionPort decisions;

  public DiagnosticAgentStep(
      DiagnosticAssessmentService diagnostics,
      LearnerRepository learners,
      ProposalGateDecisionPort decisions) {
    this.diagnostics = diagnostics;
    this.learners = learners;
    this.decisions = decisions;
  }

  @Override
  public Result diagnose(Run run) {
    String requestId = requestId(run);

    // Recovery before dispatch. Dispatch is at-most-once: an execution commissions in its own
    // transaction before the provider is called, so a worker that died after the verdict was
    // persisted cannot obtain it again by asking the model -- a replay throws
    // AI_EXECUTION_ALREADY_COMMISSIONED instead. Without this lookup that diagnosis is simply lost,
    // and every remaining attempt is spent rediscovering that it is lost.
    Optional<RecordedDecision> recorded = decisions.findDecision(requestId, ProposalType.DIAGNOSTIC);
    if (recorded.isPresent()) {
      RecordedDecision decision = recorded.orElseThrow();
      LOGGER
          .atInfo()
          .addKeyValue("operation", "workflow.diagnose.adopted")
          .addKeyValue("runId", run.id())
          .addKeyValue("requestId", requestId)
          .addKeyValue("accepted", decision.accepted())
          .log("adopted a diagnosis already recorded for this workflow request identity");
      return decision.accepted()
          ? Result.accepted("DIAGNOSIS_ACCEPTED", requestId)
          : Result.rejected("DIAGNOSIS_PROPOSAL_REJECTED", requestId);
    }

    String subject = learners.findActiveSubjectById(run.learnerId()).orElse(null);
    if (subject == null) {
      // Not retryable in any useful sense, but reported as a failure so the bounded attempt policy
      // -- rather than this adapter -- decides when to give up.
      return Result.failed("DIAGNOSIS_LEARNER_INACTIVE", null);
    }
    DiagnosticAssessmentService.Outcome outcome =
        diagnostics.assess(subject, run.curriculumVersionId(), requestId);
    return outcome.accepted()
        ? Result.accepted("DIAGNOSIS_ACCEPTED", requestId)
        : Result.rejected("DIAGNOSIS_PROPOSAL_REJECTED", requestId);
  }

  /**
   * Derived from the run, never generated fresh.
   *
   * <p>A retry of this step must present the same request identity, or the AI execution ledger
   * records a second execution for what is logically one attempt and the idempotency the dispatcher
   * and gate both rely on evaporates. It is also the key the recovery lookup above searches by, so a
   * fresh identity would make an already-durable verdict unfindable.
   */
  static String requestId(Run run) {
    return "wf-diag-" + run.id();
  }
}
