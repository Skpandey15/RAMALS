package io.ramals.learningplatform.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort.AcceptedEvaluationDecision;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort.EvaluationTarget;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Outcome;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceService;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.MasteryService;
import io.ramals.learningplatform.mastery.MasteryStatus;
import io.ramals.learningplatform.observability.StructuredLogCapture;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Status;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Step;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepClaim;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepRun;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepStatus;
import io.ramals.learningplatform.orchestration.LearningWorkflowOrchestrator.EvaluationTrigger;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.ramals.learningplatform.evidence.EvidenceCoverage;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The G-series controlled-orchestration scenarios, driven against an in-memory workflow store.
 *
 * <p>The store is a real subclass rather than a mock because these tests are about a state machine:
 * what matters is the sequence of persisted transitions, and a mock verifying calls in order would
 * assert the implementation instead of the behaviour.
 */
class LearningWorkflowOrchestratorTests {

  private static final Instant T0 = Instant.parse("2026-08-23T09:00:00Z");
  private static final UUID LEARNER = UUID.randomUUID();
  private static final UUID SKILL = UUID.randomUUID();
  private static final UUID CURRICULUM = UUID.randomUUID();
  private static final UUID ATTEMPT = UUID.randomUUID();
  private static final UUID VERSION = UUID.randomUUID();
  private static final UUID EVIDENCE_ID = UUID.randomUUID();
  private static final UUID SNAPSHOT_ID = UUID.randomUUID();

  private final EvidenceService evidence = mock(EvidenceService.class);
  private final MasteryService mastery = mock(MasteryService.class);
  private final RecordingEvaluationDecisions decisions = new RecordingEvaluationDecisions();
  private final RecordingDiagnostic diagnostic = new RecordingDiagnostic();
  private final RecordingAdaptation adaptation = new RecordingAdaptation();
  private final AtomicReference<Instant> now = new AtomicReference<>(T0);
  private final RecordingUnitOfWork unitOfWork = new RecordingUnitOfWork();
  private final InMemoryWorkflowStore store = new InMemoryWorkflowStore(() -> now.get());

  private LearningWorkflowOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    orchestrator =
        new LearningWorkflowOrchestrator(
            store,
            decisions,
            evidence,
            mastery,
            diagnostic,
            adaptation,
            unitOfWork,
            movingClock());
    when(evidence.recordEvaluationEvidence(
            any(), any(), any(), any(), anyString(), anyString(), any(), anyString()))
        .thenReturn(evidenceRow());
    when(mastery.recompute(any(), any(), any(), anyString()))
        .thenReturn(snapshot(MasteryStatus.NEEDS_PRACTICE));
    decisions.accepted =
        Optional.of(
            new AcceptedEvaluationDecision(
                "evaluation-request-1",
                new EvaluationTarget(LEARNER, SKILL, CURRICULUM, ATTEMPT, VERSION),
                new BigDecimal("0.6000"),
                "EVALUATION_SCORE_POLICY_V1",
                "interaction-1",
                "trace-1"));
  }

  private Clock movingClock() {
    return new Clock() {
      @Override
      public ZoneOffset getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        return now.get();
      }
    };
  }

  @Test
  void g01_evaluationFlowsThroughEvidenceMasteryDiagnosisAndAdaptation() {
    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    assertThat(run.status()).isEqualTo(Status.RUNNING);

    Run finished = drive(run.id());

    assertThat(finished.status()).isEqualTo(Status.COMPLETED);
    assertThat(finished.terminalReason()).isEqualTo("WORKFLOW_COMPLETED");
    assertThat(store.steps(run.id()))
        .extracting(StepRun::step, StepRun::status)
        .containsExactly(
            org.assertj.core.api.Assertions.tuple(
                Step.RECORD_EVALUATION_EVIDENCE, StepStatus.COMPLETED),
            org.assertj.core.api.Assertions.tuple(Step.RECOMPUTE_MASTERY, StepStatus.COMPLETED),
            org.assertj.core.api.Assertions.tuple(Step.DIAGNOSE, StepStatus.COMPLETED),
            org.assertj.core.api.Assertions.tuple(Step.ADAPT, StepStatus.COMPLETED));
    assertThat(diagnostic.calls).isEqualTo(1);
    assertThat(adaptation.calls).isEqualTo(1);
  }

  @Test
  void g08_everyStepIsJoinableToTheRunAndTheAgentStepsCarryARequestIdentity() {
    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    drive(run.id());

    List<StepRun> steps = store.steps(run.id());
    assertThat(steps).allSatisfy(step -> assertThat(step.runId()).isEqualTo(run.id()));
    assertThat(step(run.id(), Step.DIAGNOSE).requestId()).isNotBlank();
    assertThat(step(run.id(), Step.ADAPT).requestId()).isNotBlank();
    // The deterministic steps make no agent call, so inventing a request identity for them would
    // put a row in the correlation index that joins to no execution.
    assertThat(step(run.id(), Step.RECORD_EVALUATION_EVIDENCE).requestId()).isNull();
    assertThat(step(run.id(), Step.RECORD_EVALUATION_EVIDENCE).resultRef()).isEqualTo(EVIDENCE_ID);
    assertThat(step(run.id(), Step.RECOMPUTE_MASTERY).resultRef()).isEqualTo(SNAPSHOT_ID);
  }

  @Test
  void g02_aMasteredLearnerStopsTheWorkflowWithoutCallingAnyAgent() {
    when(mastery.recompute(any(), any(), any(), anyString()))
        .thenReturn(snapshot(MasteryStatus.MASTERED));

    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.9500"));
    Run finished = drive(run.id());

    assertThat(finished.status()).isEqualTo(Status.STOPPED);
    assertThat(finished.terminalReason()).isEqualTo("DIAGNOSIS_NOT_REQUIRED");
    assertThat(diagnostic.calls).isZero();
    assertThat(adaptation.calls).isZero();
    assertThat(step(run.id(), Step.DIAGNOSE).status()).isEqualTo(StepStatus.SKIPPED);
    assertThat(step(run.id(), Step.ADAPT).status()).isEqualTo(StepStatus.SKIPPED);
  }

  @Test
  void g03_aRejectedDiagnosisRecordsALegitimateStopRatherThanForcingAnAdaptation() {
    diagnostic.result = WorkflowAgentStep.Result.rejected("DIAGNOSIS_PROPOSAL_REJECTED", "req-d");

    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    Run finished = drive(run.id());

    assertThat(finished.status()).isEqualTo(Status.STOPPED);
    assertThat(finished.terminalReason()).isEqualTo("ADAPTATION_PROPOSAL_REJECTED");
    assertThat(step(run.id(), Step.DIAGNOSE).status()).isEqualTo(StepStatus.COMPLETED);
    assertThat(step(run.id(), Step.ADAPT).status()).isEqualTo(StepStatus.SKIPPED);
    assertThat(adaptation.calls).isZero();
  }

  @Test
  void anEvaluationTheGateRefusedNeverReachesTheEvidenceLedger() {
    decisions.accepted = Optional.empty();

    assertThatThrownBy(() -> orchestrator.trigger(trigger(Outcome.REJECTED, "0.6000")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("evaluation request is not backed by an accepted decision");
    assertThat(store.runCount()).isZero();
    verify(evidence, never())
        .recordEvaluationEvidence(any(), any(), any(), any(), anyString(), anyString(), any(), anyString());
  }

  @Test
  void triggerCopiesEveryWorkflowFactFromTheAcceptedDecisionProjection() {
    UUID otherLearner = UUID.randomUUID();
    UUID otherSkill = UUID.randomUUID();
    UUID otherCurriculum = UUID.randomUUID();
    UUID otherAttempt = UUID.randomUUID();
    UUID otherAssessment = UUID.randomUUID();
    decisions.accepted =
        Optional.of(
            new AcceptedEvaluationDecision(
                "evaluation-request-1",
                new EvaluationTarget(
                    otherLearner, otherSkill, otherCurriculum, otherAttempt, otherAssessment),
                new BigDecimal("0.3750"),
                "EVALUATION_SCORE_POLICY_V1",
                "decision-interaction",
                "decision-trace"));

    Run run = orchestrator.trigger(new EvaluationTrigger("evaluation-request-1"));

    assertThat(run.learnerId()).isEqualTo(otherLearner);
    assertThat(run.skillId()).isEqualTo(otherSkill);
    assertThat(run.curriculumVersionId()).isEqualTo(otherCurriculum);
    assertThat(run.attemptId()).isEqualTo(otherAttempt);
    assertThat(run.assessmentVersionId()).isEqualTo(otherAssessment);
    assertThat(run.normalizedScore()).isEqualByComparingTo("0.3750");
    assertThat(run.interactionId()).isEqualTo("decision-interaction");
    assertThat(run.traceId()).isEqualTo("decision-trace");
  }

  @Test
  void g04_aMidWorkflowAgentFailureLeavesEarlierAuthoritativeStepsIntactAndStaysRetryable() {
    diagnostic.failuresBeforeSuccess = 1;

    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    Run afterFailure = drive(run.id(), 3);

    // Evidence and mastery already committed and are untouched by the diagnosis failure.
    assertThat(step(run.id(), Step.RECORD_EVALUATION_EVIDENCE).status())
        .isEqualTo(StepStatus.COMPLETED);
    assertThat(step(run.id(), Step.RECOMPUTE_MASTERY).status()).isEqualTo(StepStatus.COMPLETED);
    assertThat(afterFailure.status()).isEqualTo(Status.RUNNING);
    assertThat(step(run.id(), Step.DIAGNOSE).status()).isEqualTo(StepStatus.PENDING);
    assertThat(step(run.id(), Step.DIAGNOSE).attemptCount()).isEqualTo(1);

    Run finished = drive(run.id());
    assertThat(finished.status()).isEqualTo(Status.COMPLETED);
  }

  @Test
  void g05_aDuplicateTriggerCollapsesOntoTheOneRun() {
    EvaluationTrigger trigger = trigger(Outcome.ACCEPTED, "0.6000");

    Run first = orchestrator.trigger(trigger);
    Run second = orchestrator.trigger(trigger);

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(store.runCount()).isEqualTo(1);

    drive(first.id());
    assertThat(diagnostic.calls).isEqualTo(1);
    assertThat(adaptation.calls).isEqualTo(1);
  }

  @Test
  void g06_aPermanentlyFailingStepReachesABoundedTerminalStateInsteadOfLooping() {
    diagnostic.failuresBeforeSuccess = Integer.MAX_VALUE;

    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    // Far more passes than the attempt ceiling; the run must still stop.
    Run finished = drive(run.id(), 50);

    assertThat(finished.status()).isEqualTo(Status.FAILED);
    assertThat(finished.terminalReason()).isEqualTo("STEP_ATTEMPTS_EXHAUSTED");
    assertThat(diagnostic.calls).isEqualTo(LearningWorkflowPolicy.MAX_STEP_ATTEMPTS);
    assertThat(step(run.id(), Step.DIAGNOSE).attemptCount())
        .isEqualTo(LearningWorkflowPolicy.MAX_STEP_ATTEMPTS);
    assertThat(adaptation.calls).isZero();
  }

  @Test
  void g07_anOverrunRunTimesOutWithAnExplicitTerminalStatus() {
    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    now.set(T0.plus(LearningWorkflowPolicy.RUN_DEADLINE).plus(Duration.ofSeconds(1)));

    Run finished = orchestrator.advance(run.id());

    assertThat(finished.status()).isEqualTo(Status.TIMED_OUT);
    assertThat(finished.terminalReason()).isEqualTo("RUN_DEADLINE_EXCEEDED");
    assertThat(step(run.id(), Step.RECORD_EVALUATION_EVIDENCE).status())
        .isEqualTo(StepStatus.TIMED_OUT);
    assertThat(diagnostic.calls).isZero();
  }

  @Test
  void g07_cancellationIsTerminalAndCannotBeOverwrittenByLaterProgress() {
    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));

    assertThat(orchestrator.cancel(run.id(), "CANCELLED_BY_OPERATOR")).isTrue();
    assertThat(orchestrator.cancel(run.id(), "CANCELLED_BY_OPERATOR")).isFalse();

    Run afterCancel = orchestrator.advance(run.id());
    assertThat(afterCancel.status()).isEqualTo(Status.CANCELLED);
    assertThat(afterCancel.terminalReason()).isEqualTo("CANCELLED_BY_OPERATOR");
    assertThat(diagnostic.calls).isZero();
    assertThat(adaptation.calls).isZero();
  }

  @Test
  void theSweeperExpiresOverrunRunsWithoutTouchingHealthyOnes() {
    Run healthy = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));

    assertThat(orchestrator.sweepTimeouts(10)).isZero();
    now.set(T0.plus(LearningWorkflowPolicy.RUN_DEADLINE).plus(Duration.ofSeconds(1)));
    assertThat(orchestrator.sweepTimeouts(10)).isEqualTo(1);

    assertThat(store.findById(healthy.id()).orElseThrow().status()).isEqualTo(Status.TIMED_OUT);
  }

  @Test
  void aSkippedStepRecordsNoAttempt() {
    when(mastery.recompute(any(), any(), any(), anyString()))
        .thenReturn(snapshot(MasteryStatus.MASTERED));

    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.9500"));
    drive(run.id());

    // A step that policy declined never ran, so crediting it with an attempt would make the audit
    // describe work that did not happen.
    assertThat(step(run.id(), Step.DIAGNOSE).attemptCount()).isZero();
    assertThat(step(run.id(), Step.ADAPT).attemptCount()).isZero();
    assertThat(step(run.id(), Step.RECOMPUTE_MASTERY).attemptCount()).isEqualTo(1);
  }

  @Test
  void aSkipAfterARejectedDiagnosisRecordsNoAttemptForAdaptation() {
    diagnostic.result = WorkflowAgentStep.Result.rejected("DIAGNOSIS_PROPOSAL_REJECTED", "req-d");

    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    drive(run.id());

    assertThat(step(run.id(), Step.ADAPT).status()).isEqualTo(StepStatus.SKIPPED);
    assertThat(step(run.id(), Step.ADAPT).attemptCount()).isZero();
    assertThat(step(run.id(), Step.DIAGNOSE).attemptCount()).isEqualTo(1);
  }

  @Test
  void aTimeoutBeforeAnyStepRanFabricatesNoAttempt() {
    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    now.set(T0.plus(LearningWorkflowPolicy.RUN_DEADLINE).plus(Duration.ofSeconds(1)));

    orchestrator.advance(run.id());

    StepRun first = step(run.id(), Step.RECORD_EVALUATION_EVIDENCE);
    assertThat(first.status()).isEqualTo(StepStatus.TIMED_OUT);
    assertThat(first.attemptCount()).isZero();
  }

  @Test
  void cancellationDuringARealAttemptKeepsTheAttemptCountItHad() {
    // One real attempt is spent failing, then the run is cancelled while still on that step.
    diagnostic.failuresBeforeSuccess = 1;
    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    drive(run.id(), 3);
    assertThat(step(run.id(), Step.DIAGNOSE).attemptCount()).isEqualTo(1);

    orchestrator.cancel(run.id(), "CANCELLED_BY_OPERATOR");

    StepRun diagnose = step(run.id(), Step.DIAGNOSE);
    assertThat(diagnose.status()).isEqualTo(StepStatus.CANCELLED);
    assertThat(diagnose.attemptCount()).isEqualTo(1);
  }

  @Test
  void adaptationConsumesTheSnapshotThisWorkflowProducedNotWhicheverIsLatest() {
    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    drive(run.id());

    // The id handed to adaptation is the one the recompute step recorded, so the audit trail and
    // the recommendation are computed from the same state.
    assertThat(adaptation.snapshotSeen).isEqualTo(SNAPSHOT_ID);
    assertThat(step(run.id(), Step.RECOMPUTE_MASTERY).resultRef()).isEqualTo(SNAPSHOT_ID);
  }

  @Test
  void aWorkerThatLosesItsClaimCannotCompleteTheStep() {
    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    var claim =
        store.claimStep(run.id(), Step.RECORD_EVALUATION_EVIDENCE,
            LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE).orElseThrow();

    // The run is cancelled while that claim is notionally in flight; the terminal transition clears
    // the token, so the returning worker has nothing to update.
    orchestrator.cancel(run.id(), "CANCELLED_BY_OPERATOR");

    assertThat(store.finishClaimedStep(claim, StepStatus.COMPLETED, null, null, null)).isFalse();
    assertThat(store.findById(run.id()).orElseThrow().status()).isEqualTo(Status.CANCELLED);
    assertThat(step(run.id(), Step.RECORD_EVALUATION_EVIDENCE).status())
        .isEqualTo(StepStatus.CANCELLED);
  }

  @Test
  void aSecondCallerCannotClaimAStepAnotherWorkerIsRunning() {
    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));

    var first =
        store.claimStep(run.id(), Step.RECORD_EVALUATION_EVIDENCE,
            LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE);
    var second =
        store.claimStep(run.id(), Step.RECORD_EVALUATION_EVIDENCE,
            LearningWorkflowPolicy.MAX_STEP_ATTEMPTS, LearningWorkflowPolicy.CLAIM_LEASE);

    assertThat(first).isPresent();
    assertThat(second).isEmpty();
    assertThat(step(run.id(), Step.RECORD_EVALUATION_EVIDENCE).attemptCount()).isEqualTo(1);
  }

  @Test
  void aLocalStepCommitsItsEffectInsideTheSameTransactionAsItsWorkflowMarker() {
    when(evidence.recordEvaluationEvidence(
            any(), any(), any(), any(), anyString(), anyString(), any(), anyString()))
        .thenAnswer(
            invocation -> {
              // Observed at the moment the effect runs: it must already be inside the unit of work
              // that will also record the step completion, or a crash between them loses the link.
              unitOfWork.observe("evidence");
              return evidenceRow();
            });

    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    orchestrator.advance(run.id());

    assertThat(unitOfWork.ranInsideTransaction).contains("evidence");
  }

  @Test
  void aSupersededLocalStepAbortsItsUnitOfWorkInsteadOfCommittingTheEffect() {
    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    AtomicReference<StepClaim> replacement = new AtomicReference<>();
    when(evidence.recordEvaluationEvidence(
            any(), any(), any(), any(), anyString(), anyString(), any(), anyString()))
        .thenAnswer(
            invocation -> {
              // Replace A after its effect starts but before its completion CAS. The production
              // transaction must abort rather than treating the refused CAS as a normal return.
              now.set(T0.plus(LearningWorkflowPolicy.CLAIM_LEASE));
              replacement.set(
                  store
                      .claimStep(
                          run.id(),
                          Step.RECORD_EVALUATION_EVIDENCE,
                          LearningWorkflowPolicy.MAX_STEP_ATTEMPTS,
                          LearningWorkflowPolicy.CLAIM_LEASE)
                      .orElseThrow());
              return evidenceRow();
            });

    Run afterStaleWorker = orchestrator.advance(run.id());

    assertThat(unitOfWork.rollbackCount).isEqualTo(1);
    assertThat(afterStaleWorker.status()).isEqualTo(Status.RUNNING);
    assertThat(step(run.id(), Step.RECORD_EVALUATION_EVIDENCE).status())
        .isEqualTo(StepStatus.RUNNING);
    assertThat(step(run.id(), Step.RECORD_EVALUATION_EVIDENCE).attemptCount()).isEqualTo(2);
    assertThat(step(run.id(), Step.RECORD_EVALUATION_EVIDENCE).executionToken())
        .isEqualTo(replacement.get().executionToken());
  }

  @Test
  void aRemoteStepNeverRunsInsideATransaction() {
    // The rule that matters most in this file: a database connection held across a provider call is
    // held for seconds. This asserts the orchestrator's branch, not a comment about it.
    diagnostic.observer = () -> unitOfWork.observe("diagnose");

    Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
    drive(run.id());

    assertThat(unitOfWork.ranOutsideTransaction)
        .as("the diagnostic provider call must not hold a transaction")
        .contains("diagnose");
    assertThat(unitOfWork.ranInsideTransaction).doesNotContain("diagnose");
  }

  @Test
  void workflowTransitionIsSerializableAsOneStructuredCorrelationEvent() {
    try (StructuredLogCapture logs =
        new StructuredLogCapture(LearningWorkflowOrchestrator.class)) {
      MDC.put("interactionId", "interaction-1");
      MDC.put("traceId", "trace-1");
      try {
        orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
      } finally {
        MDC.clear();
      }

      ILoggingEvent transition =
          logs.events().stream()
              .filter(event -> "controlled workflow transition".equals(event.getMessage()))
              .findFirst()
              .orElseThrow();
      String json = logs.encode(transition);
      assertThat(json).contains("\"interactionId\"").contains("\"traceId\"");
      assertThat(count(json, "\"interactionId\"")).isEqualTo(1);
      assertThat(count(json, "\"traceId\"")).isEqualTo(1);
    }
  }

  @Test
  void successfulWorkflowEventsRemainCorrelatedAcrossAnAsyncWorkerThread() throws Exception {
    try (StructuredLogCapture logs =
            new StructuredLogCapture(LearningWorkflowOrchestrator.class);
        ExecutorService worker = Executors.newSingleThreadExecutor()) {
      Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
      worker.submit(() -> drive(run.id())).get();

      List<ILoggingEvent> events = workflowEvents(logs);
      assertThat(events).isNotEmpty();
      assertThat(events.stream().map(event -> operation(logs, event)).toList())
          .contains("workflow.triggered", "workflow.advanced", "workflow.completed");
      events.forEach(event -> assertOneCanonicalCorrelationObject(logs, event, run));
    }
  }

  @Test
  void rejectedWorkflowStepIsCorrelatedWithoutASecondStructuredTraceWriter() throws Exception {
    diagnostic.result =
        WorkflowAgentStep.Result.rejected("DIAGNOSIS_PROPOSAL_REJECTED", "req-d");
    try (StructuredLogCapture logs =
            new StructuredLogCapture(LearningWorkflowOrchestrator.class);
        ExecutorService worker = Executors.newSingleThreadExecutor()) {
      Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
      worker.submit(() -> drive(run.id())).get();

      List<ILoggingEvent> events = workflowEvents(logs);
      assertThat(events.stream().map(event -> operation(logs, event)).toList())
          .contains("workflow.stopped");
      events.forEach(event -> assertOneCanonicalCorrelationObject(logs, event, run));
    }
  }

  @Test
  void failedWorkflowStepAndExceptionAreCorrelatedWithoutSerializationFailure() {
    diagnostic.failuresBeforeSuccess = 1;
    try (StructuredLogCapture logs =
        new StructuredLogCapture(LearningWorkflowOrchestrator.class)) {
      Run run = orchestrator.trigger(trigger(Outcome.ACCEPTED, "0.6000"));
      drive(run.id(), 3);

      ILoggingEvent failure =
          logs.events().stream()
              .filter(event -> "workflow step failed".equals(event.getMessage()))
              .findFirst()
              .orElseThrow();
      assertOneCanonicalCorrelationObject(logs, failure, run);
      assertThat(failure.getThrowableProxy()).isNotNull();
    }
  }

  private static List<ILoggingEvent> workflowEvents(StructuredLogCapture logs) {
    return logs.events().stream()
        .filter(event -> "controlled workflow transition".equals(event.getMessage()))
        .toList();
  }

  private static String operation(StructuredLogCapture logs, ILoggingEvent event) {
    String json = logs.encode(event);
    String marker = "\"operation\":\"";
    int start = json.indexOf(marker);
    return json.substring(start + marker.length()).split("\"", 2)[0];
  }

  private static void assertOneCanonicalCorrelationObject(
      StructuredLogCapture logs, ILoggingEvent event, Run run) {
    String json = logs.encode(event);
    assertThat(json).contains("\"interactionId\":\"" + run.interactionId() + "\"");
    assertThat(json).contains("\"traceId\":\"" + run.traceId() + "\"");
    assertThat(json).contains("\"runId\":\"" + run.id() + "\"");
    assertThat(count(json, "\"interactionId\"")).isEqualTo(1);
    assertThat(count(json, "\"traceId\"")).isEqualTo(1);
    assertThat(count(json, "\"runId\"")).isEqualTo(1);
    if ("controlled workflow transition".equals(event.getMessage())) {
      assertThat(json)
          .contains("\"evaluationRequestId\":\"" + run.evaluationRequestId() + "\"");
      assertThat(count(json, "\"evaluationRequestId\"")).isEqualTo(1);
    }
  }

  private static int count(String value, String needle) {
    int count = 0;
    for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0; offset += needle.length()) {
      count++;
    }
    return count;
  }

  /** Drives the workflow until it is terminal, with a hard cap so a stuck run fails the test. */
  private Run drive(UUID runId) {
    return drive(runId, 20);
  }

  private Run drive(UUID runId, int passes) {
    Run run = store.findById(runId).orElseThrow();
    for (int pass = 0; pass < passes && !run.status().terminal(); pass++) {
      run = orchestrator.advance(runId);
    }
    return run;
  }

  private StepRun step(UUID runId, Step step) {
    return store.step(runId, step).orElseThrow(() -> new AssertionError("no step " + step));
  }

  private static EvaluationTrigger trigger(Outcome outcome, String score) {
    return new EvaluationTrigger("evaluation-request-1");
  }

  private static Evidence evidenceRow() {
    return new Evidence(
        EVIDENCE_ID, LEARNER, SKILL, "EVALUATION", "ASSESSMENT_ATTEMPT", ATTEMPT, VERSION,
        "EVALUATION_GATE_V1", null, "lineage", new BigDecimal("0.6000"), new BigDecimal("0.6000"),
        1, 0, EvidenceCoverage.none(), "interaction-1", T0, T0);
  }

  private static MasterySnapshot snapshot(MasteryStatus status) {
    return new MasterySnapshot(
        SNAPSHOT_ID, LEARNER, SKILL, CURRICULUM, 1, new BigDecimal("0.6000"), status,
        new BigDecimal("0.8000"), new BigDecimal("0.7000"), new BigDecimal("0.7000"), 3, 5,
        "MASTERY_V1", "CONFIDENCE_V1", "STATUS_V1", null, Set.of(), "interaction-1", T0);
  }

  /** Reports whether work ran inside the transaction boundary, so tests can assert the branch. */
  private static final class RecordingUnitOfWork implements WorkflowUnitOfWork {
    private boolean open;
    private int rollbackCount;
    private final List<String> ranInsideTransaction = new ArrayList<>();
    private final List<String> ranOutsideTransaction = new ArrayList<>();

    @Override
    public void inOneTransaction(Runnable writes) {
      open = true;
      try {
        writes.run();
      } catch (RuntimeException failure) {
        rollbackCount++;
        throw failure;
      } finally {
        open = false;
      }
    }

    void observe(String what) {
      (open ? ranInsideTransaction : ranOutsideTransaction).add(what);
    }
  }

  private static final class RecordingDiagnostic implements WorkflowAgentStep.Diagnostic {
    private WorkflowAgentStep.Result result =
        WorkflowAgentStep.Result.accepted("DIAGNOSIS_ACCEPTED", "req-diagnose");
    private int failuresBeforeSuccess;
    private int calls;
    private Runnable observer;

    @Override
    public Result diagnose(Run run) {
      calls++;
      if (observer != null) {
        observer.run();
      }
      if (failuresBeforeSuccess > 0) {
        failuresBeforeSuccess--;
        return Result.failed("DIAGNOSIS_UNAVAILABLE", null);
      }
      return result;
    }
  }

  private static final class RecordingAdaptation implements WorkflowAgentStep.Adaptation {
    private int calls;
    private UUID snapshotSeen;

    @Override
    public Result adapt(Run run, UUID masterySnapshotId) {
      calls++;
      snapshotSeen = masterySnapshotId;
      return Result.accepted("ADAPTATION_WORK_ENQUEUED", "outbox-request-1");
    }
  }

  private static final class RecordingEvaluationDecisions
      implements AssessmentEvaluationDecisionPort {
    private Optional<AcceptedEvaluationDecision> accepted = Optional.empty();

    @Override
    public void append(
        AssessmentEvaluationDecisionPort.EvaluationDecisionRecord record) {
      // This test double is read-only because the orchestrator only consumes decisions.
    }

    @Override
    public Optional<AcceptedEvaluationDecision> findAcceptedByRequestId(String requestId) {
      return accepted.filter(decision -> decision.requestId().equals(requestId));
    }
  }

  /** Minimal in-memory stand-in with the same transition semantics as the JDBC store. */
  private static final class InMemoryWorkflowStore extends LearningWorkflowRepository {

    private final List<Run> runs = new ArrayList<>();
    private final List<StepRun> steps = new ArrayList<>();

    private final java.util.function.Supplier<Instant> clock;

    private InMemoryWorkflowStore(java.util.function.Supplier<Instant> clock) {
      super(null);
      this.clock = clock;
    }

    int runCount() {
      return runs.size();
    }

    @Override
    public Run startOrGet(
        String triggerKey, UUID learnerId, UUID skillId, UUID curriculumVersionId, UUID attemptId,
        UUID assessmentVersionId, BigDecimal normalizedScore, String evaluationRequestId,
        String interactionId, String traceId, Instant deadlineAt) {
      return findByTriggerKey(triggerKey)
          .orElseGet(
              () -> {
                Run run =
                    new Run(
                        UUID.randomUUID(), LearningWorkflow.TYPE_EVALUATION_TO_ADAPTATION,
                        LearningWorkflowPolicy.POLICY_VERSION, triggerKey, learnerId, skillId,
                        curriculumVersionId, attemptId, assessmentVersionId, normalizedScore,
                        evaluationRequestId, Status.RUNNING, Step.first(), null, interactionId,
                        traceId, deadlineAt, T0, null);
                runs.add(run);
                return run;
              });
    }

    @Override
    public java.util.Optional<Run> findByTriggerKey(String triggerKey) {
      return runs.stream().filter(run -> run.triggerKey().equals(triggerKey)).findFirst();
    }

    @Override
    public java.util.Optional<Run> findById(UUID runId) {
      return runs.stream().filter(run -> run.id().equals(runId)).findFirst();
    }

    @Override
    public List<Run> running(int limit) {
      return runs.stream().filter(run -> run.status() == Status.RUNNING).limit(limit).toList();
    }

    @Override
    public List<StepRun> steps(UUID runId) {
      return steps.stream()
          .filter(step -> step.runId().equals(runId))
          .sorted(java.util.Comparator.comparingInt(step -> step.step().index()))
          .toList();
    }

    @Override
    public java.util.Optional<StepRun> step(UUID runId, Step step) {
      return steps(runId).stream().filter(candidate -> candidate.step() == step).findFirst();
    }

    // Mirrors claimStep's SQL predicate exactly. The authority for these semantics is
    // LearningWorkflowConcurrencyIntegrationTests against real PostgreSQL; this copy exists so the
    // state-machine scenarios stay fast, not so the claim can be proved here.
    @Override
    public java.util.Optional<LearningWorkflow.StepClaim> claimStep(
        UUID runId, Step step, int maxAttempts, java.time.Duration lease) {
      Run run = findById(runId).orElse(null);
      if (run == null || run.status() != Status.RUNNING || run.currentStep() != step) {
        return java.util.Optional.empty();
      }
      StepRun existing = step(runId, step).orElse(null);
      UUID token = UUID.randomUUID();
      if (existing == null) {
        steps.add(
            new StepRun(
                UUID.randomUUID(), runId, step, StepStatus.RUNNING, 1, null, null, null, token,
                clock.get(), T0, null));
        return java.util.Optional.of(new LearningWorkflow.StepClaim(runId, step, 1, token));
      }
      boolean leaseExpired =
          existing.status() == StepStatus.RUNNING
              && existing.claimedAt() != null
              && !existing.claimedAt().plus(lease).isAfter(clock.get());
      boolean claimable = existing.status() == StepStatus.PENDING || leaseExpired;
      if (!claimable || existing.attemptCount() >= maxAttempts) {
        return java.util.Optional.empty();
      }
      int attempt = existing.attemptCount() + 1;
      replaceStep(
          new StepRun(
              existing.id(), runId, step, StepStatus.RUNNING, attempt, null, existing.requestId(),
              existing.resultRef(), token, clock.get(), existing.startedAt(), null));
      return java.util.Optional.of(new LearningWorkflow.StepClaim(runId, step, attempt, token));
    }

    @Override
    public boolean finishClaimedStep(
        LearningWorkflow.StepClaim claim, StepStatus status, String reasonCode, String requestId,
        UUID resultRef) {
      StepRun existing = step(claim.runId(), claim.step()).orElse(null);
      if (existing == null || !claim.executionToken().equals(existing.executionToken())) {
        return false;
      }
      replaceStep(
          new StepRun(
              existing.id(), claim.runId(), claim.step(), status, existing.attemptCount(),
              reasonCode, requestId, resultRef, null, existing.claimedAt(), existing.startedAt(), T0));
      return true;
    }

    @Override
    public boolean retryClaimedStep(LearningWorkflow.StepClaim claim, String reasonCode) {
      StepRun existing = step(claim.runId(), claim.step()).orElse(null);
      if (existing == null || !claim.executionToken().equals(existing.executionToken())) {
        return false;
      }
      replaceStep(
          new StepRun(
              existing.id(), claim.runId(), claim.step(), StepStatus.PENDING,
              existing.attemptCount(), reasonCode, existing.requestId(), existing.resultRef(),
              null, existing.claimedAt(), existing.startedAt(), null));
      return true;
    }

    @Override
    public void markSkipped(UUID runId, Step step, String reasonCode) {
      if (step(runId, step).isPresent()) {
        return;
      }
      steps.add(
          new StepRun(
              UUID.randomUUID(), runId, step, StepStatus.SKIPPED, 0, reasonCode, null, null, null,
              null, T0, T0));
    }

    @Override
    public void markCurrentStepTerminal(
        UUID runId, Step step, StepStatus status, String reasonCode) {
      StepRun existing = step(runId, step).orElse(null);
      if (existing == null) {
        steps.add(
            new StepRun(
                UUID.randomUUID(), runId, step, status, 0, reasonCode, null, null, null, null, T0, T0));
        return;
      }
      replaceStep(
          new StepRun(
              existing.id(), runId, step, status, existing.attemptCount(), reasonCode,
              existing.requestId(), existing.resultRef(), null, existing.claimedAt(),
              existing.startedAt(), T0));
    }

    @Override
    public void advanceTo(UUID runId, Step next) {
      findById(runId)
          .filter(run -> run.status() == Status.RUNNING)
          .ifPresent(
              run ->
                  replaceRun(
                      new Run(
                          run.id(), run.workflowType(), run.policyVersion(), run.triggerKey(),
                          run.learnerId(), run.skillId(), run.curriculumVersionId(),
                          run.attemptId(), run.assessmentVersionId(), run.normalizedScore(),
                          run.evaluationRequestId(), run.status(), next, run.terminalReason(),
                          run.interactionId(), run.traceId(), run.deadlineAt(), run.startedAt(),
                          run.completedAt())));
    }

    @Override
    public boolean finishRun(UUID runId, Status status, String terminalReason) {
      Run run = findById(runId).filter(candidate -> candidate.status() == Status.RUNNING).orElse(null);
      if (run == null) {
        return false;
      }
      replaceRun(
          new Run(
              run.id(), run.workflowType(), run.policyVersion(), run.triggerKey(), run.learnerId(),
              run.skillId(), run.curriculumVersionId(), run.attemptId(), run.assessmentVersionId(),
              run.normalizedScore(), run.evaluationRequestId(), status, run.currentStep(),
              terminalReason, run.interactionId(), run.traceId(), run.deadlineAt(), run.startedAt(),
              T0));
      return true;
    }

    private void replaceRun(Run replacement) {
      runs.replaceAll(run -> run.id().equals(replacement.id()) ? replacement : run);
    }

    private void replaceStep(StepRun replacement) {
      steps.replaceAll(step -> step.id().equals(replacement.id()) ? replacement : step);
    }
  }
}
