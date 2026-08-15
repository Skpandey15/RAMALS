package io.ramals.learningplatform.curriculum;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:curriculum-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class CurriculumApiContractTests {

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  CurriculumRepository repository;

  @BeforeEach
  void configureGraph() {
    when(repository.findReadableGraph("KAFKA", "v1")).thenReturn(Optional.of(graph()));
  }

  @Test
  void learnerReadsVersionedGraphAndPrerequisites() throws Exception {
    mockMvc.perform(get("/api/v1/curricula/KAFKA/versions/v1/skills")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.domainCode").value("KAFKA"))
        .andExpect(jsonPath("$.versionCode").value("v1"))
        .andExpect(jsonPath("$.skills[1].stableCode").value("KAFKA_TOPIC"));

    mockMvc.perform(get("/api/v1/curricula/KAFKA/versions/v1/skills/KAFKA_TOPIC/prerequisites")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value("KAFKA_BROKER"));
  }

  @Test
  void unauthenticatedAndAdministrativeBusinessAccessAreDenied() throws Exception {
    mockMvc.perform(get("/api/v1/curricula/KAFKA/versions/v1/skills"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/curricula/KAFKA/versions/v1/skills")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  private CurriculumGraph graph() {
    CurriculumGraph.SkillNode broker = skill("KAFKA_BROKER", 1, List.of());
    CurriculumGraph.SkillNode topic = skill("KAFKA_TOPIC", 2, List.of("KAFKA_BROKER"));
    return new CurriculumGraph(UUID.randomUUID(), "KAFKA", "v1", "PUBLISHED", List.of(broker, topic));
  }

  private CurriculumGraph.SkillNode skill(String code, int order, List<String> prerequisites) {
    return new CurriculumGraph.SkillNode(
        UUID.randomUUID(), code, code, "Description", "FOUNDATIONAL",
        new BigDecimal("0.8000"), 20, new BigDecimal("0.8000"),
        new BigDecimal("0.7500"), 5, List.of("QUIZ"), List.of("EASY"), order,
        List.of(new CurriculumGraph.Objective(code + "_OBJECTIVE", "Objective", true, 1)),
        prerequisites);
  }
}
