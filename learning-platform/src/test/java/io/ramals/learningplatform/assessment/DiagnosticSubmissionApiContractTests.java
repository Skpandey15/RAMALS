package io.ramals.learningplatform.assessment;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ramals.learningplatform.evidence.EvidenceService;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:diagnostic-submit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class DiagnosticSubmissionApiContractTests {

  private static final UUID LEARNER_ONE = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID LEARNER_TWO = UUID.fromString("01900000-0000-7000-8000-0000000000a2");
  private static final UUID VERSION = UUID.fromString("01900000-0000-7000-8000-000000000402");
  private static final UUID ATTEMPT = UUID.fromString("01900000-0000-7000-8000-0000000004f1");
  private static final UUID ITEM = UUID.fromString("01900000-0000-7000-8000-000000000411");
  private static final UUID OTHER_ITEM = UUID.fromString("01900000-0000-7000-8000-000000000499");
  private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
  private static final String URL = "/api/v1/diagnostics/kafka/attempts/" + ATTEMPT + "/submit";

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  AssessmentRepository assessmentRepository;

  @MockitoBean
  LearnerRepository learnerRepository;

  @MockitoBean
  EvidenceService evidenceService;

  private static JwtRequestPostProcessor learner(String subject) {
    return jwt()
        .jwt(token -> token.subject(subject))
        .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"));
  }

  private static AssessmentAttempt attempt(String status) {
    return new AssessmentAttempt(ATTEMPT, LEARNER_ONE, VERSION, status, "key-1", NOW, NOW);
  }

  private static ResolvedDiagnostic diagnostic() {
    return new ResolvedDiagnostic(VERSION, "KAFKA", "KAFKA_DIAGNOSTIC", "v1", "PUBLISHED");
  }

  private static AssessmentItemScoringView view() {
    return new AssessmentItemScoringView(
        ITEM, "KAFKA_BROKER", "SINGLE_CHOICE", List.of("A", "B", "C", "D"), List.of("B"));
  }

  private static String body(UUID itemId, String option) {
    return "{\"responses\":[{\"itemId\":\"" + itemId + "\",\"selectedOptions\":[\"" + option + "\"]}]}";
  }

  @Test
  void submitRequiresAuthentication() throws Exception {
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(ITEM, "B")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void submitRequiresLearnerRole() throws Exception {
    mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(ITEM, "B"))
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void submitScoresPersistsAndCompletes() throws Exception {
    when(learnerRepository.findBySubject("user-1"))
        .thenReturn(Optional.of(new Learner(LEARNER_ONE, "user-1", "ACTIVE", NOW, NOW)));
    when(assessmentRepository.findAttemptForUpdate(ATTEMPT)).thenReturn(Optional.of(attempt("IN_PROGRESS")));
    when(assessmentRepository.findDiagnosticByVersionId(VERSION)).thenReturn(Optional.of(diagnostic()));
    when(assessmentRepository.findItemScoringViews(VERSION)).thenReturn(List.of(view()));
    when(assessmentRepository.completeAttempt(ATTEMPT)).thenReturn(true);
    when(assessmentRepository.findScoredResponses(ATTEMPT))
        .thenReturn(List.of(new ScoredResponse("KAFKA_BROKER", 4, true)));

    mockMvc.perform(post(URL).with(learner("user-1"))
            .contentType(MediaType.APPLICATION_JSON).content(body(ITEM, "B")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.scoringVersion").value("DIAGNOSTIC_SCORING_V1"))
        .andExpect(jsonPath("$.itemsAnswered").value(1))
        .andExpect(jsonPath("$.skillScores[0].skillCode").value("KAFKA_BROKER"))
        .andExpect(jsonPath("$.skillScores[0].itemsCorrect").value(1));

    verify(assessmentRepository).insertResponse(
        ArgumentMatchers.eq(ATTEMPT), ArgumentMatchers.eq(ITEM),
        ArgumentMatchers.anyString(), ArgumentMatchers.eq(true));
    verify(assessmentRepository).completeAttempt(ATTEMPT);
  }

  @Test
  void duplicateSubmitReturnsSameResultWithoutRewriting() throws Exception {
    when(learnerRepository.findBySubject("user-1"))
        .thenReturn(Optional.of(new Learner(LEARNER_ONE, "user-1", "ACTIVE", NOW, NOW)));
    when(assessmentRepository.findAttemptForUpdate(ATTEMPT)).thenReturn(Optional.of(attempt("COMPLETED")));
    when(assessmentRepository.findDiagnosticByVersionId(VERSION)).thenReturn(Optional.of(diagnostic()));
    when(assessmentRepository.findScoredResponses(ATTEMPT))
        .thenReturn(List.of(new ScoredResponse("KAFKA_BROKER", 4, true)));

    mockMvc.perform(post(URL).with(learner("user-1"))
            .contentType(MediaType.APPLICATION_JSON).content(body(ITEM, "B")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.skillScores[0].skillCode").value("KAFKA_BROKER"));

    verify(assessmentRepository, never()).insertResponse(
        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
        ArgumentMatchers.anyBoolean());
    verify(assessmentRepository, never()).completeAttempt(ATTEMPT);
  }

  @Test
  void submittingToAbandonedAttemptConflicts() throws Exception {
    when(learnerRepository.findBySubject("user-1"))
        .thenReturn(Optional.of(new Learner(LEARNER_ONE, "user-1", "ACTIVE", NOW, NOW)));
    when(assessmentRepository.findAttemptForUpdate(ATTEMPT)).thenReturn(Optional.of(attempt("ABANDONED")));
    when(assessmentRepository.findDiagnosticByVersionId(VERSION)).thenReturn(Optional.of(diagnostic()));

    mockMvc.perform(post(URL).with(learner("user-1"))
            .contentType(MediaType.APPLICATION_JSON).content(body(ITEM, "B")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_ATTEMPT_STATE"));
  }

  @Test
  void unknownItemIsUnprocessable() throws Exception {
    when(learnerRepository.findBySubject("user-1"))
        .thenReturn(Optional.of(new Learner(LEARNER_ONE, "user-1", "ACTIVE", NOW, NOW)));
    when(assessmentRepository.findAttemptForUpdate(ATTEMPT)).thenReturn(Optional.of(attempt("IN_PROGRESS")));
    when(assessmentRepository.findDiagnosticByVersionId(VERSION)).thenReturn(Optional.of(diagnostic()));
    when(assessmentRepository.findItemScoringViews(VERSION)).thenReturn(List.of(view()));

    mockMvc.perform(post(URL).with(learner("user-1"))
            .contentType(MediaType.APPLICATION_JSON).content(body(OTHER_ITEM, "B")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("UNKNOWN_ASSESSMENT_ITEM"));

    verify(assessmentRepository, never()).completeAttempt(ATTEMPT);
  }

  @Test
  void invalidOptionSelectionIsUnprocessable() throws Exception {
    when(learnerRepository.findBySubject("user-1"))
        .thenReturn(Optional.of(new Learner(LEARNER_ONE, "user-1", "ACTIVE", NOW, NOW)));
    when(assessmentRepository.findAttemptForUpdate(ATTEMPT)).thenReturn(Optional.of(attempt("IN_PROGRESS")));
    when(assessmentRepository.findDiagnosticByVersionId(VERSION)).thenReturn(Optional.of(diagnostic()));
    when(assessmentRepository.findItemScoringViews(VERSION)).thenReturn(List.of(view()));

    mockMvc.perform(post(URL).with(learner("user-1"))
            .contentType(MediaType.APPLICATION_JSON).content(body(ITEM, "Z")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("INVALID_SUBMISSION"));
  }

  @Test
  void emptyResponsesFailValidation() throws Exception {
    mockMvc.perform(post(URL).with(learner("user-1"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"responses\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void attemptOwnedByAnotherLearnerIsNotFound() throws Exception {
    when(learnerRepository.findBySubject("user-2"))
        .thenReturn(Optional.of(new Learner(LEARNER_TWO, "user-2", "ACTIVE", NOW, NOW)));
    when(assessmentRepository.findAttemptForUpdate(ATTEMPT)).thenReturn(Optional.of(attempt("IN_PROGRESS")));

    mockMvc.perform(post(URL).with(learner("user-2"))
            .contentType(MediaType.APPLICATION_JSON).content(body(ITEM, "B")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FOUND"));
  }
}
