package io.ramals.learningplatform.recommendation;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:recommendation-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class RecommendationApiContractTests {

  private static final UUID LEARNER_ONE = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  RecommendationRepository recommendationRepository;

  @MockitoBean
  LearnerRepository learnerRepository;

  private static JwtRequestPostProcessor learner(String subject) {
    return jwt()
        .jwt(token -> token.subject(subject))
        .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"));
  }

  @Test
  void recommendationsRequireAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/me/recommendations")).andExpect(status().isUnauthorized());
  }

  @Test
  void recommendationsRequireLearnerRole() throws Exception {
    mockMvc.perform(get("/api/v1/me/recommendations")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void learnerReadsOwnCurrentRecommendations() throws Exception {
    when(learnerRepository.findBySubject("user-1"))
        .thenReturn(Optional.of(new Learner(LEARNER_ONE, "user-1", "ACTIVE", NOW, NOW)));
    when(recommendationRepository.findCurrentByLearner(LEARNER_ONE)).thenReturn(List.of(
        new LearningRecommendation(
            UUID.randomUUID(), LEARNER_ONE, UUID.randomUUID(), "KAFKA_BROKER", UUID.randomUUID(),
            RecommendedAction.COLLECT_EVIDENCE, "INSUFFICIENT_EVIDENCE", "INSUFFICIENT_EVIDENCE",
            UUID.randomUUID(), UUID.randomUUID(), NOW)));

    mockMvc.perform(get("/api/v1/me/recommendations").with(learner("user-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recommendations[0].skillCode").value("KAFKA_BROKER"))
        .andExpect(jsonPath("$.recommendations[0].recommendedAction").value("COLLECT_EVIDENCE"))
        .andExpect(jsonPath("$.recommendations[0].reasonCode").value("INSUFFICIENT_EVIDENCE"))
        .andExpect(jsonPath("$.recommendations[0].decisionRecordId").isNotEmpty());
  }

  @Test
  void unprovisionedLearnerHasNoRecommendations() throws Exception {
    when(learnerRepository.findBySubject("user-2")).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/v1/me/recommendations").with(learner("user-2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recommendations").isEmpty());
  }
}
