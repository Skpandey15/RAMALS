package io.ramals.learningplatform.learning;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ramals.learningplatform.curriculum.CurriculumGraph;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:progression-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class ProgressionApiContractTests {

  private static final UUID LEARNER_ONE = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID BROKER = UUID.fromString("01900000-0000-7000-8000-000000000101");
  private static final UUID TOPIC = UUID.fromString("01900000-0000-7000-8000-000000000102");
  private static final UUID CV = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
  private static final String URL = "/api/v1/me/progression/KAFKA/versions/v1";

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  CurriculumRepository curriculumRepository;

  @MockitoBean
  LearnerRepository learnerRepository;

  @MockitoBean
  ProgressionRepository progressionRepository;

  private static JwtRequestPostProcessor learner(String subject) {
    return jwt()
        .jwt(token -> token.subject(subject))
        .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"));
  }

  private static CurriculumGraph.SkillNode skill(UUID id, String code, int order, List<String> prerequisites) {
    return new CurriculumGraph.SkillNode(
        id, code, code, "Description", "FOUNDATIONAL", new BigDecimal("0.8000"), 20,
        new BigDecimal("0.8000"), new BigDecimal("0.7500"), 5, List.of("QUIZ"), List.of("EASY"),
        order, List.of(new CurriculumGraph.Objective(code + "_OBJ", "Objective", true, 1)),
        prerequisites);
  }

  @BeforeEach
  void configureGraph() {
    CurriculumGraph graph = new CurriculumGraph(CV, "KAFKA", "v1", "PUBLISHED", List.of(
        skill(BROKER, "KAFKA_BROKER", 1, List.of()),
        skill(TOPIC, "KAFKA_TOPIC", 2, List.of("KAFKA_BROKER"))));
    when(curriculumRepository.findReadableGraph("KAFKA", "v1")).thenReturn(Optional.of(graph));
    when(learnerRepository.findBySubject("user-1"))
        .thenReturn(Optional.of(new Learner(LEARNER_ONE, "user-1", "ACTIVE", NOW, NOW)));
    when(progressionRepository.everMasteredSkillIds(LEARNER_ONE, CV)).thenReturn(Set.of());
    when(progressionRepository.retentionDueSkillIds(org.mockito.ArgumentMatchers.eq(LEARNER_ONE),
        org.mockito.ArgumentMatchers.eq(CV), org.mockito.ArgumentMatchers.any())).thenReturn(Set.of());
  }

  @Test
  void progressionRequiresAuthentication() throws Exception {
    mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
  }

  @Test
  void progressionRequiresLearnerRole() throws Exception {
    mockMvc.perform(get(URL).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void dependentSkillIsLockedUntilPrerequisiteMastered() throws Exception {
    when(progressionRepository.latestStatuses(LEARNER_ONE, CV)).thenReturn(Map.of());

    mockMvc.perform(get(URL).with(learner("user-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skills[0].skillCode").value("KAFKA_BROKER"))
        .andExpect(jsonPath("$.skills[0].state").value("ELIGIBLE"))
        .andExpect(jsonPath("$.skills[1].skillCode").value("KAFKA_TOPIC"))
        .andExpect(jsonPath("$.skills[1].state").value("LOCKED"))
        .andExpect(jsonPath("$.skills[1].reasonCode").value("PREREQUISITE_BLOCKED"));
  }

  @Test
  void dependentSkillBecomesEligibleWhenPrerequisiteMastered() throws Exception {
    when(progressionRepository.latestStatuses(LEARNER_ONE, CV))
        .thenReturn(Map.of(BROKER, MasteryStatus.MASTERED));

    mockMvc.perform(get(URL).with(learner("user-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skills[0].state").value("MASTERED"))
        .andExpect(jsonPath("$.skills[1].state").value("ELIGIBLE"))
        .andExpect(jsonPath("$.skills[1].reasonCode").value("PREREQUISITE_READY"));
  }
}
