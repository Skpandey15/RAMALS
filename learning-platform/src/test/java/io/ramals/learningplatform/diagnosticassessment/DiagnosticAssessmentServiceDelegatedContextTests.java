package io.ramals.learningplatform.diagnosticassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.ai.AiDelegatedCapabilityPolicy;
import io.ramals.learningplatform.ai.DelegatedAiContextMinter;
import io.ramals.learningplatform.ai.DelegatedAiExecutionContext;
import io.ramals.learningplatform.ai.DiagnosticAssessmentPort;
import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import io.ramals.learningplatform.ai.contract.DiagnosticDispatchAuthorization;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.curriculum.CurriculumGraph;
import io.ramals.learningplatform.curriculum.CurriculumService;
import io.ramals.learningplatform.execution.AiExecution;
import io.ramals.learningplatform.execution.AiExecutionCommission;
import io.ramals.learningplatform.execution.AiExecutionDispatchClaim;
import io.ramals.learningplatform.execution.DiagnosticAssessmentExecutionRecorder;
import io.ramals.learningplatform.execution.DiagnosticCommissionContext;
import io.ramals.learningplatform.grounding.AuthorizedGroundingFacts;
import io.ramals.learningplatform.grounding.GroundedContext;
import io.ramals.learningplatform.grounding.GroundedContextFactory;
import io.ramals.learningplatform.grounding.GroundedContextValidator;
import io.ramals.learningplatform.grounding.GroundingRetrievalPolicy;
import io.ramals.learningplatform.grounding.GroundingRetrievalPort;
import io.ramals.learningplatform.grounding.GroundingRetrievalService;
import io.ramals.learningplatform.grounding.ProposalGroundingGate;
import io.ramals.learningplatform.grounding.ProposalGroundingPolicy;
import io.ramals.learningplatform.mcp.auth.DelegatedLearnerContextIssuer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP-3.1: {@link DiagnosticAssessmentService} mints its delegated learner-context credential from
 * exactly the grounded context's own opaque learner reference and the curriculum's own domain code
 * -- never a caller-supplied value, and never propagating a minting failure into the diagnostic
 * assessment call itself.
 */
class DiagnosticAssessmentServiceDelegatedContextTests {

  private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
  private static final UUID CURRICULUM = UUID.randomUUID();
  private static final UUID LEARNER_ID = UUID.fromString("01900000-0000-7000-8000-000000000091");

  private final CurriculumService curriculumService = mock(CurriculumService.class);
  private final DelegatedAiContextMinter minter = mock(DelegatedAiContextMinter.class);

  /** Captures the {@link DelegatedAiExecutionContext} the outbound call actually received --
   * a lambda cannot do this here, because a lambda can only implement an interface's abstract
   * method (the 3-argument one), never override its 4-argument default. */
  private static final class CapturingPort implements DiagnosticAssessmentPort {
    private final AiProposalEnvelope response;
    private DelegatedAiExecutionContext capturedContext = DelegatedAiExecutionContext.NONE;

    CapturingPort(AiProposalEnvelope response) {
      this.response = response;
    }

    @Override
    public AiProposalEnvelope requestDiagnosticAssessment(
        DiagnosticAssessmentRequest request, DiagnosticDispatchAuthorization authorization,
        long deadlineMillis) {
      return requestDiagnosticAssessment(
          request, authorization, deadlineMillis, DelegatedAiExecutionContext.NONE);
    }

    @Override
    public AiProposalEnvelope requestDiagnosticAssessment(
        DiagnosticAssessmentRequest request, DiagnosticDispatchAuthorization authorization,
        long deadlineMillis, DelegatedAiExecutionContext delegatedContext) {
      this.capturedContext = delegatedContext;
      return response;
    }
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<Supplier<String>> domainSupplierCaptor() {
    return ArgumentCaptor.forClass(Supplier.class);
  }

  private DiagnosticAssessmentService service(DiagnosticAssessmentPort port) {
    return new DiagnosticAssessmentService(
        retrieval(), port, gate(), recordingExecutions(), recordingWriter(),
        Clock.fixed(NOW, ZoneOffset.UTC), curriculumService, minter);
  }

  private static DiagnosticAssessmentProposalGate gate() {
    return new DiagnosticAssessmentProposalGate(
        new ProposalGroundingGate(
            new GroundedContextValidator(JsonMapper.builder().findAndAddModules().build()),
            new ProposalGroundingPolicy()));
  }

  private GroundingRetrievalService retrieval() {
    GroundingRetrievalPort retrievalPort =
        new GroundingRetrievalPort() {
          @Override
          public Optional<AuthorizedGroundingFacts> retrieve(
              String authenticatedSubject, UUID curriculumVersionId, Instant asOf,
              GroundingRetrievalPolicy policy) {
            return Optional.of(
                new AuthorizedGroundingFacts(
                    LEARNER_ID, DiagnosticAssessmentProposalGateTests.context().items()));
          }

          @Override
          public void appendRetrievalRecord(GroundedContext context, UUID learnerId) {
            // Not asserted here.
          }
        };
    return new GroundingRetrievalService(
        retrievalPort,
        new GroundedContextFactory(
            new GroundedContextValidator(JsonMapper.builder().findAndAddModules().build())),
        GroundingRetrievalPolicy.V1, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static DiagnosticAssessmentExecutionRecorder recordingExecutions() {
    return new DiagnosticAssessmentExecutionRecorder() {
      @Override
      public AiExecutionCommission commission(DiagnosticAssessmentRequest request) {
        return AiExecutionCommission.claimed();
      }

      @Override
      public Optional<DiagnosticCommissionContext> findRecoverableCommission(String requestId) {
        return Optional.empty();
      }

      @Override
      public AiExecutionDispatchClaim acquireDispatch(String requestId) {
        return AiExecutionDispatchClaim.acquired(
            UUID.fromString("01900000-0000-7000-8000-0000000000f1"), 1, "a".repeat(64));
      }

      @Override
      public boolean markProviderInvocationStarted(String requestId, AiExecutionDispatchClaim claim) {
        return true;
      }

      @Override
      public AiExecution recordSuccess(
          DiagnosticAssessmentRequest request, AiProposalEnvelope proposal, Instant startedAt,
          Instant completedAt) {
        return null;
      }

      @Override
      public AiExecution recordFailure(
          DiagnosticAssessmentRequest request, String errorCode, Instant startedAt,
          Instant completedAt) {
        return null;
      }

      @Override
      public AiExecution recordIndeterminate(
          DiagnosticAssessmentRequest request, String errorCode, Instant startedAt,
          Instant completedAt) {
        return null;
      }
    };
  }

  private static DiagnosticOutcomeWriter recordingWriter() {
    return (request, envelope, startedAt, completedAt, decision) -> { };
  }

  private static AiProposalEnvelope acceptingEnvelope() {
    return new AiProposalEnvelope(
        "1.0", "p-1", AgentType.DIAGNOSTIC, "DIAGNOSTIC_ASSESSMENT_AGENT_V1", "run-1",
        "DIAGNOSTIC_ASSESSMENT", "DIAGNOSTIC_ASSESSMENT_PROMPT_V1", "diagnostic-default",
        TrustLevel.NON_AUTHORITATIVE, null, null, Map.of(), null, null);
  }

  private static void setUpCorrelation() {
    MDC.put("interactionId", "interaction-1");
    MDC.put("traceId", "trace-1");
  }

  private void stubDomain(String domainCode) {
    CurriculumGraph graph = new CurriculumGraph(CURRICULUM, domainCode, "v1", "PUBLISHED", List.of());
    when(curriculumService.graph(CURRICULUM)).thenReturn(graph);
  }

  // -- learner scope: the grounded context's own opaque ref, never the raw authenticated subject ----

  @Test
  void mintsWithTheGroundedContextsOwnOpaqueLearnerRefNeverTheRawSubject() {
    setUpCorrelation();
    stubDomain("KAFKA");
    when(minter.mint(any(), any(), any(), any())).thenReturn(DelegatedAiExecutionContext.NONE);
    DiagnosticAssessmentService service = service(new CapturingPort(acceptingEnvelope()));

    service.assess("subject-should-never-be-the-scope", CURRICULUM, "r-1");

    ArgumentCaptor<String> learnerScope = ArgumentCaptor.forClass(String.class);
    verify(minter).mint(eq("interaction-1"), learnerScope.capture(), any(), any());
    assertThat(learnerScope.getValue()).isNotEqualTo("subject-should-never-be-the-scope");
  }

  // -- domain scope: resolved through CurriculumService.graph(curriculumVersionId), never free text --

  @Test
  void domainScopeIsResolvedThroughCurriculumServiceForTheRequestsCurriculumVersion() {
    setUpCorrelation();
    stubDomain("CBSE_MATH");
    when(minter.mint(any(), any(), any(), any())).thenReturn(DelegatedAiExecutionContext.NONE);
    DiagnosticAssessmentService service = service(new CapturingPort(acceptingEnvelope()));

    service.assess("subject-1", CURRICULUM, "r-1");

    ArgumentCaptor<Supplier<String>> supplier = domainSupplierCaptor();
    verify(minter).mint(any(), any(), supplier.capture(), any());
    assertThat(supplier.getValue().get()).isEqualTo("CBSE_MATH");
  }

  // -- capabilities: exactly the diagnostic-assessment policy, never widened -------------------------

  @Test
  void mintsWithExactlyTheDiagnosticAssessmentCapabilityAllowlist() {
    setUpCorrelation();
    stubDomain("KAFKA");
    when(minter.mint(any(), any(), any(), any())).thenReturn(DelegatedAiExecutionContext.NONE);
    DiagnosticAssessmentService service = service(new CapturingPort(acceptingEnvelope()));

    service.assess("subject-1", CURRICULUM, "r-1");

    verify(minter).mint(
        any(), any(), any(), eq(AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES));
  }

  // -- the resulting delegated context reaches the outbound call unchanged --------------------------

  @Test
  void theMintedContextIsPassedIntoTheOutboundCallUnchanged() {
    setUpCorrelation();
    stubDomain("KAFKA");
    DelegatedAiExecutionContext minted = new DelegatedAiExecutionContext(Optional.of("a-token"));
    when(minter.mint(any(), any(), any(), any())).thenReturn(minted);
    CapturingPort port = new CapturingPort(acceptingEnvelope());
    DiagnosticAssessmentService service = service(port);

    service.assess("subject-1", CURRICULUM, "r-1");

    assertThat(port.capturedContext).isEqualTo(minted);
  }

  // -- a minting failure never fails the diagnostic assessment call itself ---------------------------

  @Test
  void aDomainResolutionFailureDoesNotFailTheDiagnosticAssessmentCall() {
    setUpCorrelation();
    // A real, enabled minter this time (not the mock field): curriculumService.graph(CURRICULUM)
    // genuinely throws, and this proves the exception never reaches assess()'s own caller -- not
    // merely that the minter's own unit tests catch it in isolation.
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    DelegatedLearnerContextIssuer realIssuer = new DelegatedLearnerContextIssuer(
        "ramals-learning-platform", "ramals-mcp", Duration.ofSeconds(120), "key-1", key,
        Clock.fixed(NOW, ZoneOffset.UTC));
    DelegatedAiContextMinter enabledMinter = new DelegatedAiContextMinter(Optional.of(realIssuer));
    when(curriculumService.graph(CURRICULUM))
        .thenThrow(new IllegalStateException("no readable curriculum graph"));
    DiagnosticAssessmentService service = new DiagnosticAssessmentService(
        retrieval(), new CapturingPort(acceptingEnvelope()), gate(), recordingExecutions(),
        recordingWriter(), Clock.fixed(NOW, ZoneOffset.UTC), curriculumService, enabledMinter);

    DiagnosticAssessmentService.Outcome outcome = service.assess("subject-1", CURRICULUM, "r-1");

    assertThat(outcome).isNotNull();
  }

  // -- MCP disabled (a DelegatedAiContextMinter.disabled()) preserves existing behavior --------------

  @Test
  void aDisabledMinterPreservesExistingDiagnosticAssessmentBehavior() {
    setUpCorrelation();
    stubDomain("KAFKA");
    CapturingPort port = new CapturingPort(acceptingEnvelope());
    DiagnosticAssessmentService service = new DiagnosticAssessmentService(
        retrieval(), port, gate(), recordingExecutions(), recordingWriter(),
        Clock.fixed(NOW, ZoneOffset.UTC), curriculumService, DelegatedAiContextMinter.disabled());

    service.assess("subject-1", CURRICULUM, "r-1");

    assertThat(port.capturedContext.token()).isEmpty();
  }

  // -- the pre-MCP-3.1 six-argument constructor never mints at all -----------------------------------

  @Test
  void theSixArgumentConstructorNeverAttemptsToMint() {
    setUpCorrelation();
    CapturingPort port = new CapturingPort(acceptingEnvelope());
    DiagnosticAssessmentService service = new DiagnosticAssessmentService(
        retrieval(), port, gate(), recordingExecutions(), recordingWriter(),
        Clock.fixed(NOW, ZoneOffset.UTC));

    // curriculumService is never touched by this constructor's own internal Optional -- proven by
    // the call completing at all, since a real dereference of a null CurriculumService would NPE.
    service.assess("subject-1", CURRICULUM, "r-1");

    assertThat(port.capturedContext.token()).isEmpty();
  }
}
