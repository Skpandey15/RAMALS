package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.DomainContext;
import io.ramals.learningplatform.ai.contract.DomainType;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The endpoint the web client has always called.
 *
 * <p>Every one of these fails on the candidate M1-T18 validated. {@code TutorService} existed, was a
 * live bean and was wired to the AI plane; {@code tutorApi.ts} existed and posted to
 * {@code /api/v1/tutor/explain}; nothing joined them. A learner asking for an explanation received
 * {@code 500 UNEXPECTED_ERROR} from a route that was never registered, and the backend logged
 * {@code NoResourceFoundException} at ERROR with a stack trace.
 *
 * <p>What made that survivable for so long is that no test asked the question this class asks. The
 * service had thorough unit tests, the client had thorough component tests, and each passed against
 * its own half of a contract nothing checked end to end.
 */
@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:tutor-endpoint;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class TutorEndpointApiTests {

  private static final String PATH = "/api/v1/tutor/explain";
  private static final String BODY = """
      {"skillCode":"ALG-1","masteryStatus":"GAP"}""";

  @Autowired
  MockMvc mockMvc;

  /**
   * The port, not the service. Stubbing here leaves the controller, the service, the outcome mapping
   * and the response contract all under test — replacing the service would test only that a bean
   * with the right name exists.
   */
  @MockitoBean
  TutorPort tutorPort;

  /**
   * Stubbed only to keep the test off the database. The suite runs without a schema, and the real
   * assembler reads the curriculum graph -- a DataAccessException there would surface as the same
   * 500 this class exists to distinguish from a missing route.
   */
  @MockitoBean
  DomainContextAssembler domainContextAssembler;

  private static MockHttpServletRequestBuilder asLearner() {
    return post(PATH)
        .contentType("application/json")
        .content(BODY)
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LEARNER")));
  }

  @org.junit.jupiter.api.BeforeEach
  void domainIsKnown() {
    when(domainContextAssembler.forSkill(any()))
        .thenReturn(java.util.Optional.of(new DomainContext("MATH", DomainType.ACADEMIC, "v1")));
  }

  private static AiProposalEnvelope proposal(String explanation, List<String> checks) {
    return new AiProposalEnvelope(
        "1.0", "proposal-1", AgentType.TUTOR, "1.0.0", "run-1",
        "TUTOR_EXPLAIN", "v1", "ci-fake", TrustLevel.NON_AUTHORITATIVE, "HIGH", List.of(),
        Map.of("responseType", "EXPLANATION",
               "explanation", explanation,
               "checksForUnderstanding", checks),
        null, null);
  }

  @Test
  void theEndpointTheClientCallsExists() throws Exception {
    when(tutorPort.requestTutorResponse(any(), anyLong()))
        .thenReturn(proposal("Factorising groups common terms.", List.of("Try 6x + 9.")));

    // Before this endpoint existed the same request produced 500 UNEXPECTED_ERROR.
    mockMvc.perform(asLearner()).andExpect(status().isOk());
  }

  @Test
  void aProposalIsReturnedInTheShapeTheClientParses() throws Exception {
    when(tutorPort.requestTutorResponse(any(), anyLong()))
        .thenReturn(proposal("Factorising groups common terms.", List.of("Try 6x + 9.", "And 4y - 8.")));

    mockMvc.perform(asLearner())
        .andExpect(status().isOk())
        // tutorApi.ts reads `outcome` first and falls through to the proposal, so the discriminator
        // must always be present -- an absent field would be parsed as a proposal.
        .andExpect(jsonPath("$.outcome").value("PROPOSED"))
        .andExpect(jsonPath("$.explanation").value("Factorising groups common terms."))
        .andExpect(jsonPath("$.checksForUnderstanding.length()").value(2))
        .andExpect(jsonPath("$.supportCode").isNotEmpty());
  }

  @Test
  void aDegradedTutorIsAnOutcomeRatherThanAnError() throws Exception {
    when(tutorPort.requestTutorResponse(any(), anyLong()))
        .thenThrow(new AiUnavailableException("CIRCUIT_OPEN", "circuit open"));

    // 200 with an outcome, not 5xx. A learner losing their tutor must not lose their session, and
    // tutorApi.ts maps any non-OK response onto AI_TRANSPORT_FAILURE regardless of the real reason.
    mockMvc.perform(asLearner())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("UNAVAILABLE"))
        .andExpect(jsonPath("$.reason").isNotEmpty())
        .andExpect(jsonPath("$.supportCode").isNotEmpty())
        .andExpect(jsonPath("$.explanation").doesNotExist());
  }

  @Test
  void theLearnerIsNeverNamedToTheAiPlane() throws Exception {
    when(tutorPort.requestTutorResponse(any(), anyLong()))
        .thenReturn(proposal("x", List.of()));

    mockMvc.perform(asLearner().with(jwt().jwt(token -> token.subject("learner-alice"))
        .authorities(new SimpleGrantedAuthority("ROLE_LEARNER")))).andExpect(status().isOk());
    mockMvc.perform(asLearner().with(jwt().jwt(token -> token.subject("learner-alice"))
        .authorities(new SimpleGrantedAuthority("ROLE_LEARNER")))).andExpect(status().isOk());

    ArgumentCaptor<AiRequestEnvelope> sent = ArgumentCaptor.forClass(AiRequestEnvelope.class);
    org.mockito.Mockito.verify(tutorPort, org.mockito.Mockito.times(2))
        .requestTutorResponse(sent.capture(), anyLong());

    List<AiRequestEnvelope> envelopes = sent.getAllValues();
    for (AiRequestEnvelope envelope : envelopes) {
      assertThat(envelope.learner().learnerRef())
          .as("the token subject must not be forwarded to the AI plane")
          .isNotEqualTo("learner-alice")
          .doesNotContain("alice");
    }
    assertThat(envelopes.get(0).learner().learnerRef())
        .as("a reference stable across requests would let the plane link calls it cannot otherwise "
            + "correlate, which is the property the envelope's own comment promises")
        .isNotEqualTo(envelopes.get(1).learner().learnerRef());
  }

  @Test
  void tutoringRequiresAuthentication() throws Exception {
    mockMvc.perform(post(PATH).contentType("application/json").content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tutoringRequiresTheLearnerRole() throws Exception {
    mockMvc.perform(post(PATH).contentType("application/json").content(BODY)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTENT_AUTHOR"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void anIncompleteRequestIsRejectedRatherThanSentToThePlane() throws Exception {
    mockMvc.perform(post(PATH).contentType("application/json").content("{\"skillCode\":\"ALG-1\"}")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
        .andExpect(status().isBadRequest());

    org.mockito.Mockito.verifyNoInteractions(tutorPort);
  }

  @Test
  void anUnmappedPathIsNotFoundRatherThanAServerFailure() throws Exception {
    // The second defect M1-T18 found, and the reason the first was hard to read: an authenticated
    // request to a route that does not exist was reported as 500 UNEXPECTED_ERROR, so "you asked for
    // something that is not here" and "we broke" were the same response.
    mockMvc.perform(get("/api/v1/definitely-not-a-real-path")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        // A 404 must confirm nothing about what does exist.
        .andExpect(jsonPath("$.detail").value("The requested resource does not exist."))
        .andExpect(jsonPath("$.interactionId").isNotEmpty());
  }
}
