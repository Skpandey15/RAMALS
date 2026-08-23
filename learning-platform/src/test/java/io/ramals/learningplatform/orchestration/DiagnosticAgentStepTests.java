package io.ramals.learningplatform.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.diagnosticassessment.DiagnosticAssessmentService;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort.RecordedDecision;
import io.ramals.learningplatform.grounding.ProposalType;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Status;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Step;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Crash recovery for the one step that cannot simply be replayed.
 *
 * <p>Dispatch is at-most-once by construction: the execution commissions in its own transaction
 * before the provider is called. That is deliberate and must not be weakened -- so a worker that
 * dies after the verdict was persisted has to recover it by looking it up, because asking the model
 * again is not available to it.
 */
class DiagnosticAgentStepTests {

  private static final UUID RUN_ID = UUID.fromString("01900000-0000-7000-8000-0000000000aa");
  private static final UUID LEARNER = UUID.randomUUID();

  private final DiagnosticAssessmentService diagnostics = mock(DiagnosticAssessmentService.class);
  private final LearnerRepository learners = mock(LearnerRepository.class);
  private final ProposalGateDecisionPort decisions = mock(ProposalGateDecisionPort.class);
  private final DiagnosticAgentStep step =
      new DiagnosticAgentStep(diagnostics, learners, decisions);

  @Test
  void anAlreadyRecordedAcceptanceIsAdoptedWithoutCallingTheModelAgain() {
    when(decisions.findDecision("wf-diag-" + RUN_ID, ProposalType.DIAGNOSTIC))
        .thenReturn(Optional.of(recorded(true)));

    WorkflowAgentStep.Result result = step.diagnose(run());

    assertThat(result.succeeded()).isTrue();
    assertThat(result.accepted()).isTrue();
    assertThat(result.reasonCode()).isEqualTo("DIAGNOSIS_ACCEPTED");
    assertThat(result.requestId()).isEqualTo("wf-diag-" + RUN_ID);
    verify(diagnostics, never()).assess(anyString(), any(), anyString());
  }

  @Test
  void anAlreadyRecordedRejectionIsAdoptedRatherThanRetried() {
    // A rejection is a completed outcome, not a failure. Re-dispatching would either throw on the
    // commissioning guard or, worse, spend a second model call to be told the same thing.
    when(decisions.findDecision("wf-diag-" + RUN_ID, ProposalType.DIAGNOSTIC))
        .thenReturn(Optional.of(recorded(false)));

    WorkflowAgentStep.Result result = step.diagnose(run());

    assertThat(result.succeeded()).isTrue();
    assertThat(result.accepted()).isFalse();
    assertThat(result.reasonCode()).isEqualTo("DIAGNOSIS_PROPOSAL_REJECTED");
    verify(diagnostics, never()).assess(anyString(), any(), anyString());
  }

  @Test
  void withNoRecordedDecisionTheAgentIsDispatchedNormally() {
    when(decisions.findDecision(anyString(), any())).thenReturn(Optional.empty());
    when(learners.findActiveSubjectById(LEARNER)).thenReturn(Optional.of("subject-1"));
    when(diagnostics.assess(anyString(), any(), anyString()))
        .thenReturn(
            new DiagnosticAssessmentService.Outcome(true, List.of(), "p-1", "r-1", "ctx-1"));

    WorkflowAgentStep.Result result = step.diagnose(run());

    assertThat(result.accepted()).isTrue();
    verify(diagnostics).assess("subject-1", run().curriculumVersionId(), "wf-diag-" + RUN_ID);
  }

  @Test
  void theLookupKeyIsTheStableWorkflowRequestIdentity() {
    // A fresh identity per attempt would make an already-durable verdict unfindable, which is the
    // whole failure this recovery exists to prevent.
    when(decisions.findDecision(anyString(), any())).thenReturn(Optional.empty());
    when(learners.findActiveSubjectById(LEARNER)).thenReturn(Optional.of("subject-1"));
    when(diagnostics.assess(anyString(), any(), anyString()))
        .thenReturn(
            new DiagnosticAssessmentService.Outcome(true, List.of(), "p-1", "r-1", "ctx-1"));

    step.diagnose(run());
    step.diagnose(run());

    verify(decisions, org.mockito.Mockito.times(2))
        .findDecision("wf-diag-" + RUN_ID, ProposalType.DIAGNOSTIC);
  }

  @Test
  void anInactiveLearnerIsReportedAsAFailureRatherThanDecidedHere() {
    when(decisions.findDecision(anyString(), any())).thenReturn(Optional.empty());
    when(learners.findActiveSubjectById(LEARNER)).thenReturn(Optional.empty());

    WorkflowAgentStep.Result result = step.diagnose(run());

    assertThat(result.succeeded()).isFalse();
    assertThat(result.reasonCode()).isEqualTo("DIAGNOSIS_LEARNER_INACTIVE");
    verify(diagnostics, never()).assess(anyString(), any(), anyString());
  }

  private static RecordedDecision recorded(boolean accepted) {
    return new RecordedDecision(
        "wf-diag-" + RUN_ID, "proposal-1", "run-1", "ctx-1", accepted, List.of("REASON_CODE"));
  }

  private static Run run() {
    return new Run(
        RUN_ID,
        LearningWorkflow.TYPE_EVALUATION_TO_ADAPTATION,
        LearningWorkflowPolicy.POLICY_VERSION,
        "trigger",
        LEARNER,
        UUID.fromString("01900000-0000-7000-8000-0000000000bb"),
        UUID.fromString("01900000-0000-7000-8000-0000000000cc"),
        UUID.fromString("01900000-0000-7000-8000-0000000000dd"),
        UUID.fromString("01900000-0000-7000-8000-0000000000ee"),
        new BigDecimal("0.6000"),
        "evaluation-request-1",
        Status.RUNNING,
        Step.DIAGNOSE,
        null,
        "interaction-1",
        "trace-1",
        Instant.parse("2026-08-23T10:00:00Z"),
        Instant.parse("2026-08-23T09:00:00Z"),
        null);
  }
}
