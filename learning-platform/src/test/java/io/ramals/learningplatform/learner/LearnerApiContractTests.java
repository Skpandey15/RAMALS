package io.ramals.learningplatform.learner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ramals.learningplatform.observability.CorrelationHeaders;
import io.ramals.learningplatform.observability.UuidV7;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:learner-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class LearnerApiContractTests {

  private static final UUID LEARNER_ONE = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID LEARNER_TWO = UUID.fromString("01900000-0000-7000-8000-0000000000a2");
  private static final UUID KAFKA_DOMAIN = UUID.fromString("01900000-0000-7000-8000-000000000001");

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  LearnerRepository repository;

  private static JwtRequestPostProcessor learner(String subject) {
    return jwt()
        .jwt(token -> token.subject(subject))
        .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"));
  }

  private static Learner learnerRow(UUID id, String subject) {
    Instant now = Instant.parse("2026-08-15T00:00:00Z");
    return new Learner(id, subject, "ACTIVE", now, now);
  }

  @Test
  void profileRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/me/profile")).andExpect(status().isUnauthorized());
  }

  @Test
  void profileRequiresLearnerRole() throws Exception {
    mockMvc.perform(get("/api/v1/me/profile")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void learnerReadsOwnProfileAndCorrelationIdIsEchoed() throws Exception {
    when(repository.provisionForSubject("user-1")).thenReturn(learnerRow(LEARNER_ONE, "user-1"));
    String interactionId = UuidV7.generate().toString();

    mockMvc.perform(get("/api/v1/me/profile")
            .with(learner("user-1"))
            .header(CorrelationHeaders.INTERACTION_ID, interactionId))
        .andExpect(status().isOk())
        .andExpect(header().string(CorrelationHeaders.INTERACTION_ID, interactionId))
        .andExpect(jsonPath("$.learnerId").value(LEARNER_ONE.toString()))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void ownershipIsDerivedFromSubjectNotFromClientInput() throws Exception {
    when(repository.provisionForSubject("user-1")).thenReturn(learnerRow(LEARNER_ONE, "user-1"));
    when(repository.provisionForSubject("user-2")).thenReturn(learnerRow(LEARNER_TWO, "user-2"));

    mockMvc.perform(get("/api/v1/me/profile").with(learner("user-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.learnerId").value(LEARNER_ONE.toString()));
    mockMvc.perform(get("/api/v1/me/profile").with(learner("user-2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.learnerId").value(LEARNER_TWO.toString()));
  }

  @Test
  void goalNotSetReturnsProblemDetails() throws Exception {
    when(repository.provisionForSubject("user-1")).thenReturn(learnerRow(LEARNER_ONE, "user-1"));
    when(repository.findGoal(LEARNER_ONE)).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/v1/me/goal").with(learner("user-1")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GOAL_NOT_SET"));
  }

  @Test
  void settingGoalPersistsAndReturnsResolvedDomain() throws Exception {
    Instant now = Instant.parse("2026-08-15T00:00:00Z");
    when(repository.provisionForSubject("user-1")).thenReturn(learnerRow(LEARNER_ONE, "user-1"));
    when(repository.findActiveDomainId("KAFKA")).thenReturn(Optional.of(KAFKA_DOMAIN));
    when(repository.upsertGoal(eq(LEARNER_ONE), eq(KAFKA_DOMAIN), any(), any()))
        .thenReturn(new LearnerGoal(
            LEARNER_ONE, "KAFKA", new BigDecimal("0.8500"), null, now, now));

    mockMvc.perform(put("/api/v1/me/goal")
            .with(learner("user-1"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetDomainCode\":\"KAFKA\",\"targetProficiency\":0.85}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetDomainCode").value("KAFKA"));
  }

  @Test
  void invalidGoalPayloadIsRejectedBeforeReachingTheDomain() throws Exception {
    mockMvc.perform(put("/api/v1/me/goal")
            .with(learner("user-1"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetDomainCode\":\"not a code\",\"targetProficiency\":1.5}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void unknownDomainIsRejectedAsUnprocessable() throws Exception {
    when(repository.provisionForSubject("user-1")).thenReturn(learnerRow(LEARNER_ONE, "user-1"));
    when(repository.findActiveDomainId("GHOST")).thenReturn(Optional.empty());

    mockMvc.perform(put("/api/v1/me/goal")
            .with(learner("user-1"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetDomainCode\":\"GHOST\",\"targetProficiency\":0.8}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("UNKNOWN_LEARNING_DOMAIN"));
  }
}
