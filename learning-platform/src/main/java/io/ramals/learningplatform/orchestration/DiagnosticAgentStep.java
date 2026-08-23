package io.ramals.learningplatform.orchestration;

import io.ramals.learningplatform.diagnosticassessment.DiagnosticAssessmentService;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;
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

  private final DiagnosticAssessmentService diagnostics;
  private final LearnerRepository learners;

  public DiagnosticAgentStep(
      DiagnosticAssessmentService diagnostics, LearnerRepository learners) {
    this.diagnostics = diagnostics;
    this.learners = learners;
  }

  @Override
  public Result diagnose(Run run) {
    String subject = learners.findActiveSubjectById(run.learnerId()).orElse(null);
    if (subject == null) {
      // Not retryable in any useful sense, but reported as a failure so the bounded attempt policy
      // -- rather than this adapter -- decides when to give up.
      return Result.failed("DIAGNOSIS_LEARNER_INACTIVE", null);
    }
    String requestId = requestId(run);
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
   * and gate both rely on evaporates.
   */
  static String requestId(Run run) {
    return "wf-diag-" + run.id();
  }
}
