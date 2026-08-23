package io.ramals.learningplatform.assessmentevaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ramals.learningplatform.assessmentevaluation.AssessmentFeedbackReadModel.RubricResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
    properties = {
      "RAMALS_DB_URL=jdbc:h2:mem:assessment-feedback-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "RAMALS_DB_USER=sa",
      "RAMALS_DB_PASSWORD=",
      "spring.flyway.enabled=false"
    })
@AutoConfigureMockMvc
class AssessmentFeedbackApiContractTests {

  private static final String PATH = "/api/v1/me/assessment-evaluations/latest-feedback";

  @Autowired MockMvc mockMvc;

  @MockitoBean AssessmentFeedbackRepository repository;

  @Test
  void endpointRequiresLearnerRole() throws Exception {
    mockMvc
        .perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void learnerReceivesOnlyApprovedMinimizedPayloadFromOwnSubject() throws Exception {
    when(repository.findLatestForSubject("learner-subject"))
        .thenReturn(
            Optional.of(
                new AssessmentFeedbackReadModel(
                    "ACCEPTED",
                    "answer-v1",
                    "rubric-v1",
                    "Approved overall feedback.",
                    List.of(
                        new RubricResult(
                            "accuracy",
                            new BigDecimal("2"),
                            new BigDecimal("4"),
                            "Use the exact acknowledgement semantics.")),
                    Instant.parse("2026-08-23T00:00:00Z"))));

    MvcResult result =
        mockMvc
            .perform(
                get(PATH)
                    .with(
                        jwt()
                            .jwt(token -> token.subject("learner-subject"))
                            .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("EVALUATED"))
            .andExpect(jsonPath("$.approvedFeedback.feedback").value("Approved overall feedback."))
            .andExpect(jsonPath("$.approvedFeedback.rubricResults[0].dimensionId").value("accuracy"))
            .andExpect(jsonPath("$.approvedFeedback.nextLearningRationale").isString())
            .andReturn();

    verify(repository).findLatestForSubject("learner-subject");
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain(
            "traceId",
            "interactionId",
            "reasonCodes",
            "parserReasonCode",
            "policyVersion",
            "confidence",
            "evidenceIds",
            "agentRunId",
            "requestId",
            "proposalId");
  }

  @Test
  void rejectedDecisionReturnsNoCandidateFeedbackOrInternalDiagnostics() throws Exception {
    when(repository.findLatestForSubject("learner-subject"))
        .thenReturn(
            Optional.of(
                new AssessmentFeedbackReadModel(
                    "REJECTED",
                    "answer-v1",
                    "rubric-v1",
                    "Unsafe candidate content.",
                    List.of(),
                    Instant.parse("2026-08-23T00:00:00Z"))));

    mockMvc
        .perform(
            get(PATH)
                .with(
                    jwt()
                        .jwt(token -> token.subject("learner-subject"))
                        .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.approvedFeedback").isEmpty())
        .andExpect(result -> assertThat(result.getResponse().getContentAsString())
            .doesNotContain("Unsafe candidate content."));
  }

  @Test
  void learnerWithNoOwnedDecisionReceivesNonEnumeratingUnavailableState() throws Exception {
    when(repository.findLatestForSubject("other-learner")).thenReturn(Optional.empty());

    mockMvc
        .perform(
            get(PATH)
                .with(
                    jwt()
                        .jwt(token -> token.subject("other-learner"))
                        .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
        .andExpect(jsonPath("$.approvedFeedback").isEmpty());
  }
}
