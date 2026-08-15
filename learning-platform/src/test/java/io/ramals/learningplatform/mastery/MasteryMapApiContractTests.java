package io.ramals.learningplatform.mastery;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ramals.learningplatform.curriculum.CurriculumGraph;
import io.ramals.learningplatform.curriculum.CurriculumRepository;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:mastery-map-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class MasteryMapApiContractTests {

  private static final UUID LEARNER_ONE = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID CV = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  MasteryRepository masteryRepository;

  @MockitoBean
  LearnerRepository learnerRepository;

  @MockitoBean
  CurriculumRepository curriculumRepository;

  @Test
  void masteryMapRequiresLearnerRole() throws Exception {
    mockMvc.perform(get("/api/v1/me/mastery/KAFKA/versions/v1")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void learnerReadsMasteryScoreConfidenceAndStatus() throws Exception {
    CurriculumGraph graph = new CurriculumGraph(CV, "KAFKA", "v1", "PUBLISHED", List.of());
    when(curriculumRepository.findReadableGraph("KAFKA", "v1")).thenReturn(Optional.of(graph));
    when(learnerRepository.findBySubject("user-1"))
        .thenReturn(Optional.of(new Learner(LEARNER_ONE, "user-1", "ACTIVE", NOW, NOW)));
    when(masteryRepository.latestMasteryMap(LEARNER_ONE, CV)).thenReturn(List.of(
        new MasteryMapEntry("KAFKA_BROKER", new BigDecimal("1.0000"), new BigDecimal("0.3300"),
            "INSUFFICIENT_EVIDENCE", 1)));

    mockMvc.perform(get("/api/v1/me/mastery/KAFKA/versions/v1")
            .with(jwt().jwt(token -> token.subject("user-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skills[0].skillCode").value("KAFKA_BROKER"))
        .andExpect(jsonPath("$.skills[0].masteryStatus").value("INSUFFICIENT_EVIDENCE"))
        .andExpect(jsonPath("$.skills[0].aggregateVersion").value(1));
  }
}
