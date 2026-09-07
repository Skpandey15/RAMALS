package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.execution.AiExecutionCommission;
import io.ramals.learningplatform.execution.AiExecutionRecoveryPort;
import io.ramals.learningplatform.execution.AiExecutionRecorder;
import io.ramals.learningplatform.execution.ClaimedAgentWork;
import io.ramals.learningplatform.recommendation.RecommendationDecision;
import io.ramals.learningplatform.recommendation.RecommendedAction;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * MCP-3.1: {@link AdaptationOutboxProcessor} mints its delegated learner-context credential from
 * exactly the authoritative claim fields, never a caller-supplied or otherwise derived value.
 */
class AdaptationOutboxProcessorDelegatedContextTests {

  private final AdaptationService adaptation = mock(AdaptationService.class);
  private final AiExecutionRecorder executions = mock(AiExecutionRecorder.class);
  private final AiExecutionRecoveryPort recovery = mock(AiExecutionRecoveryPort.class);
  private final CurriculumService curriculumService = mock(CurriculumService.class);
  private final DelegatedAiContextMinter minter = mock(DelegatedAiContextMinter.class);

  private final AdaptationOutboxProcessor processor = new AdaptationOutboxProcessor(
      adaptation, executions, recovery, curriculumService, minter);

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<Supplier<String>> domainSupplierCaptor() {
    return ArgumentCaptor.forClass(Supplier.class);
  }

  private static ClaimedAgentWork work(UUID learnerId, UUID skillId, String interactionId) {
    return new ClaimedAgentWork(
        UUID.randomUUID(), "request-1", interactionId, "trace-1", "ADAPTATION", "ADAPT",
        UUID.randomUUID(), learnerId, skillId, RecommendedAction.PRACTICE, "POLICY", 1, "worker-a");
  }

  private void allowDispatch() {
    when(executions.commission(any(), eq("ADAPTATION"))).thenReturn(AiExecutionCommission.claimed());
    when(adaptation.compareRequired(any(), any(RecommendationDecision.class), anyLong(), any()))
        .thenReturn(new AdaptationService.Outcome(
            new RecommendationDecision(RecommendedAction.PRACTICE, "POLICY"), null, false));
  }

  // -- learner scope: the claim's own authoritative learnerId, verbatim -----------------------------

  @Test
  void mintsWithTheClaimsOwnLearnerIdAsTheLearnerScope() {
    allowDispatch();
    UUID learnerId = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
    UUID skillId = UUID.randomUUID();
    when(curriculumService.domainCodeForSkill(skillId)).thenReturn(Optional.of("KAFKA"));
    when(minter.mint(any(), any(), any(), any())).thenReturn(DelegatedAiExecutionContext.NONE);

    processor.process(work(learnerId, skillId, "interaction-1"));

    verify(minter).mint(eq("interaction-1"), eq(learnerId.toString()), any(), any());
  }

  // -- domain scope: resolved through CurriculumService, never a free-text value ---------------------

  @Test
  void domainScopeIsResolvedThroughCurriculumServiceForTheClaimsSkill() {
    allowDispatch();
    UUID skillId = UUID.randomUUID();
    when(curriculumService.domainCodeForSkill(skillId)).thenReturn(Optional.of("CBSE_MATH"));
    when(minter.mint(any(), any(), any(), any())).thenReturn(DelegatedAiExecutionContext.NONE);

    processor.process(work(UUID.randomUUID(), skillId, "interaction-1"));

    ArgumentCaptor<Supplier<String>> supplier = domainSupplierCaptor();
    verify(minter).mint(any(), any(), supplier.capture(), any());
    assertThat(supplier.getValue().get()).isEqualTo("CBSE_MATH");
  }

  // -- capabilities: exactly the adaptation policy, never widened ------------------------------------

  @Test
  void mintsWithExactlyTheAdaptationCapabilityAllowlist() {
    allowDispatch();
    UUID skillId = UUID.randomUUID();
    when(curriculumService.domainCodeForSkill(skillId)).thenReturn(Optional.of("KAFKA"));
    when(minter.mint(any(), any(), any(), any())).thenReturn(DelegatedAiExecutionContext.NONE);

    processor.process(work(UUID.randomUUID(), skillId, "interaction-1"));

    verify(minter).mint(any(), any(), any(), eq(AiDelegatedCapabilityPolicy.ADAPTATION_CAPABILITIES));
  }

  // -- interaction scope: the claim's own interactionId, never a fresh one ---------------------------

  @Test
  void mintsWithTheClaimsOwnInteractionIdNeverAFreshOne() {
    allowDispatch();
    UUID skillId = UUID.randomUUID();
    when(curriculumService.domainCodeForSkill(skillId)).thenReturn(Optional.of("KAFKA"));
    when(minter.mint(any(), any(), any(), any())).thenReturn(DelegatedAiExecutionContext.NONE);

    processor.process(work(UUID.randomUUID(), skillId, "the-exact-claim-interaction-id"));

    verify(minter).mint(eq("the-exact-claim-interaction-id"), any(), any(), any());
  }

  // -- different learners/domains produce different minted scopes -----------------------------------

  @Test
  void differentLearnersProduceDifferentLearnerScopeArguments() {
    allowDispatch();
    UUID learnerA = UUID.randomUUID();
    UUID learnerB = UUID.randomUUID();
    UUID skillId = UUID.randomUUID();
    when(curriculumService.domainCodeForSkill(skillId)).thenReturn(Optional.of("KAFKA"));
    when(minter.mint(any(), any(), any(), any())).thenReturn(DelegatedAiExecutionContext.NONE);

    processor.process(work(learnerA, skillId, "interaction-1"));
    processor.process(work(learnerB, skillId, "interaction-2"));

    verify(minter).mint(eq("interaction-1"), eq(learnerA.toString()), any(), any());
    verify(minter).mint(eq("interaction-2"), eq(learnerB.toString()), any(), any());
  }

  // -- the resulting delegated context (whatever the minter returns) reaches the AI call itself ------

  @Test
  void theMintedContextIsPassedIntoTheAdaptationCallUnchanged() {
    allowDispatch();
    UUID skillId = UUID.randomUUID();
    when(curriculumService.domainCodeForSkill(skillId)).thenReturn(Optional.of("KAFKA"));
    DelegatedAiExecutionContext minted =
        new DelegatedAiExecutionContext(Optional.of("a-minted-token"));
    when(minter.mint(any(), any(), any(), any())).thenReturn(minted);
    when(adaptation.compareRequired(any(), any(RecommendationDecision.class), anyLong(), eq(minted)))
        .thenReturn(new AdaptationService.Outcome(
            new RecommendationDecision(RecommendedAction.PRACTICE, "POLICY"), null, false));

    processor.process(work(UUID.randomUUID(), skillId, "interaction-1"));

    verify(adaptation).compareRequired(any(), any(), anyLong(), eq(minted));
  }
}
