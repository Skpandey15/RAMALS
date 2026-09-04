package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:diagnostic-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class DiagnosticApiContractTests {

  private static final UUID LEARNER_ONE = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID LEARNER_TWO = UUID.fromString("01900000-0000-7000-8000-0000000000a2");
  private static final UUID VERSION = UUID.fromString("01900000-0000-7000-8000-000000000402");
  private static final UUID ATTEMPT = UUID.fromString("01900000-0000-7000-8000-0000000004f1");
  private static final UUID ITEM = UUID.fromString("01900000-0000-7000-8000-000000000411");
  private static final UUID SECOND_ITEM = UUID.fromString("01900000-0000-7000-8000-000000000412");
  private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
  private static final String POLICY = DiagnosticFormSelector.SELECTION_POLICY_VERSION;

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  AssessmentRepository assessmentRepository;

  @MockitoBean
  LearnerRepository learnerRepository;

  private static JwtRequestPostProcessor learner(String subject) {
    return jwt()
        .jwt(token -> token.subject(subject))
        .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"));
  }

  private static Learner learnerRow(UUID id, String subject) {
    return new Learner(id, subject, "ACTIVE", NOW, NOW);
  }

  private static ResolvedDiagnostic diagnostic() {
    return new ResolvedDiagnostic(VERSION, "KAFKA", "KAFKA_DIAGNOSTIC", "v1", "PUBLISHED");
  }

  private static List<EligibleItem> pool() {
    return List.of(
        new EligibleItem(ITEM, "KAFKA_BROKER", "FOUNDATIONAL", null),
        new EligibleItem(SECOND_ITEM, "KAFKA_TOPIC", "INTERMEDIATE", null));
  }

  private static AssessmentAttempt attempt(String key) {
    return new AssessmentAttempt(ATTEMPT, LEARNER_ONE, VERSION, "IN_PROGRESS", key, NOW, NOW);
  }

  @Test
  void createRequiresAuthentication() throws Exception {
    mockMvc.perform(post("/api/v1/diagnostics/kafka/attempts").header("Idempotency-Key", "k1"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void createRequiresLearnerRole() throws Exception {
    mockMvc.perform(post("/api/v1/diagnostics/kafka/attempts")
            .header("Idempotency-Key", "k1")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void missingIdempotencyKeyIsRejected() throws Exception {
    mockMvc.perform(post("/api/v1/diagnostics/kafka/attempts").with(learner("user-1")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
  }

  @Test
  void firstCreateIs201AndRetryWithSameKeyIs200() throws Exception {
    when(learnerRepository.provisionForSubject("user-1")).thenReturn(learnerRow(LEARNER_ONE, "user-1"));
    when(assessmentRepository.findPublishedDiagnostic("KAFKA")).thenReturn(Optional.of(diagnostic()));
    when(assessmentRepository.findByIdempotency(eq(LEARNER_ONE), eq(VERSION), eq("key-1")))
        .thenReturn(Optional.empty(), Optional.of(attempt("key-1")));
    when(assessmentRepository.findActiveAttempt(LEARNER_ONE, VERSION)).thenReturn(Optional.empty());
    when(assessmentRepository.insertAttempt(LEARNER_ONE, VERSION, "key-1", POLICY, null))
        .thenReturn(attempt("key-1"));
    when(assessmentRepository.findEligibleItems(eq(VERSION), eq(LEARNER_ONE), any()))
        .thenReturn(pool());

    mockMvc.perform(post("/api/v1/diagnostics/kafka/attempts")
            .header("Idempotency-Key", "key-1").with(learner("user-1")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.attemptId").value(ATTEMPT.toString()))
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.idempotencyKey").value("key-1"));

    mockMvc.perform(post("/api/v1/diagnostics/kafka/attempts")
            .header("Idempotency-Key", "key-1").with(learner("user-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attemptId").value(ATTEMPT.toString()));
  }

  @Test
  void differentKeyWhileActiveReusesTheActiveAttempt() throws Exception {
    when(learnerRepository.provisionForSubject("user-1")).thenReturn(learnerRow(LEARNER_ONE, "user-1"));
    when(assessmentRepository.findPublishedDiagnostic("KAFKA")).thenReturn(Optional.of(diagnostic()));
    when(assessmentRepository.findByIdempotency(eq(LEARNER_ONE), eq(VERSION), eq("key-2")))
        .thenReturn(Optional.empty());
    when(assessmentRepository.findActiveAttempt(LEARNER_ONE, VERSION))
        .thenReturn(Optional.of(attempt("key-1")));

    mockMvc.perform(post("/api/v1/diagnostics/kafka/attempts")
            .header("Idempotency-Key", "key-2").with(learner("user-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attemptId").value(ATTEMPT.toString()));
  }

  @Test
  void concurrentInsertRaceResolvesToExistingAttempt() throws Exception {
    when(learnerRepository.provisionForSubject("user-1")).thenReturn(learnerRow(LEARNER_ONE, "user-1"));
    when(assessmentRepository.findPublishedDiagnostic("KAFKA")).thenReturn(Optional.of(diagnostic()));
    when(assessmentRepository.findByIdempotency(eq(LEARNER_ONE), eq(VERSION), eq("key-1")))
        .thenReturn(Optional.empty(), Optional.of(attempt("key-1")));
    when(assessmentRepository.findActiveAttempt(LEARNER_ONE, VERSION)).thenReturn(Optional.empty());
    when(assessmentRepository.insertAttempt(LEARNER_ONE, VERSION, "key-1", POLICY, null))
        .thenThrow(new DuplicateKeyException("concurrent create"));

    mockMvc.perform(post("/api/v1/diagnostics/kafka/attempts")
            .header("Idempotency-Key", "key-1").with(learner("user-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attemptId").value(ATTEMPT.toString()));
  }

  @Test
  void unknownDomainReturnsNotFound() throws Exception {
    when(learnerRepository.provisionForSubject("user-1")).thenReturn(learnerRow(LEARNER_ONE, "user-1"));
    when(assessmentRepository.findPublishedDiagnostic("GHOST")).thenReturn(Optional.empty());

    mockMvc.perform(post("/api/v1/diagnostics/ghost/attempts")
            .header("Idempotency-Key", "key-1").with(learner("user-1")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("DIAGNOSTIC_NOT_FOUND"));
  }

  @Test
  void attemptDetailReturnsItemsButNeverTheAnswerKey() throws Exception {
    when(learnerRepository.findBySubject("user-1")).thenReturn(Optional.of(learnerRow(LEARNER_ONE, "user-1")));
    when(assessmentRepository.findAttempt(ATTEMPT)).thenReturn(Optional.of(attempt("key-1")));
    when(assessmentRepository.findDiagnosticByVersionId(VERSION)).thenReturn(Optional.of(diagnostic()));
    when(assessmentRepository.findPresentedItems(ATTEMPT, VERSION)).thenReturn(List.of(new DiagnosticItem(
        ITEM, "KAFKA_DIAG_BROKER", "KAFKA_BROKER", "SINGLE_CHOICE", "Which responsibility?",
        List.of(new DiagnosticItemOption("A", "Wrong"), new DiagnosticItemOption("B", "Right")), 1)));

    mockMvc.perform(get("/api/v1/diagnostics/kafka/attempts/" + ATTEMPT).with(learner("user-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attemptId").value(ATTEMPT.toString()))
        .andExpect(jsonPath("$.items[0].itemCode").value("KAFKA_DIAG_BROKER"))
        .andExpect(jsonPath("$.items[0].options[0].id").value("A"))
        .andExpect(jsonPath("$.items[0].answerKey").doesNotExist())
        .andExpect(content().string(not(containsString("answer"))))
        .andExpect(content().string(not(containsString("correct"))));
  }

  @Test
  void attemptCreationPersistsTheSelectedForm() throws Exception {
    when(learnerRepository.provisionForSubject("user-1")).thenReturn(learnerRow(LEARNER_ONE, "user-1"));
    when(assessmentRepository.findPublishedDiagnostic("KAFKA")).thenReturn(Optional.of(diagnostic()));
    when(assessmentRepository.findByIdempotency(eq(LEARNER_ONE), eq(VERSION), eq("key-1")))
        .thenReturn(Optional.empty());
    when(assessmentRepository.findActiveAttempt(LEARNER_ONE, VERSION)).thenReturn(Optional.empty());
    when(assessmentRepository.insertAttempt(LEARNER_ONE, VERSION, "key-1", POLICY, null))
        .thenReturn(attempt("key-1"));
    when(assessmentRepository.findEligibleItems(eq(VERSION), eq(LEARNER_ONE), any()))
        .thenReturn(pool());

    mockMvc.perform(post("/api/v1/diagnostics/kafka/attempts")
            .header("Idempotency-Key", "key-1").with(learner("user-1")))
        .andExpect(status().isCreated());

    // Both pool items are covering their own skill, so the whole pool is the form -- and every one
    // of them is written down, at a position, before the response reaches the learner.
    ArgumentCaptor<List<SelectedItem>> selected = ArgumentCaptor.captor();
    verify(assessmentRepository).insertSelectedItems(eq(ATTEMPT), selected.capture());
    assertThat(selected.getValue())
        .extracting(SelectedItem::itemVersionId)
        .containsExactlyInAnyOrder(ITEM, SECOND_ITEM);
    assertThat(selected.getValue())
        .extracting(SelectedItem::presentationOrder)
        .containsExactlyInAnyOrder(1, 2);
  }

  @Test
  void attemptOwnedByAnotherLearnerIsNotFound() throws Exception {
    when(learnerRepository.findBySubject("user-2")).thenReturn(Optional.of(learnerRow(LEARNER_TWO, "user-2")));
    when(assessmentRepository.findAttempt(ATTEMPT)).thenReturn(Optional.of(attempt("key-1")));

    mockMvc.perform(get("/api/v1/diagnostics/kafka/attempts/" + ATTEMPT).with(learner("user-2")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FOUND"));
  }

  @Test
  void malformedAttemptIdIsNotFound() throws Exception {
    mockMvc.perform(get("/api/v1/diagnostics/kafka/attempts/not-a-uuid").with(learner("user-1")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FOUND"));
  }
}
