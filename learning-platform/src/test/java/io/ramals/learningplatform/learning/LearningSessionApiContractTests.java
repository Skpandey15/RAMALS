package io.ramals.learningplatform.learning;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:session-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class LearningSessionApiContractTests {

  private static final UUID LEARNER_ONE = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID LEARNER_TWO = UUID.fromString("01900000-0000-7000-8000-0000000000a2");
  private static final UUID CV = UUID.fromString("01900000-0000-7000-8000-000000000002");
  private static final UUID SESSION = UUID.fromString("01900000-0000-7000-8000-0000000005f1");
  private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  LearningSessionRepository sessionRepository;

  @MockitoBean
  LearnerRepository learnerRepository;

  @MockitoBean
  CurriculumRepository curriculumRepository;

  private static JwtRequestPostProcessor learner(String subject) {
    return jwt()
        .jwt(token -> token.subject(subject))
        .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"));
  }

  private static LearningSession session(LearningSessionStatus statusValue, int version) {
    return new LearningSession(SESSION, LEARNER_ONE, CV, "KAFKA", "v1", statusValue, version,
        MAPPER.createObjectNode(), NOW, NOW, null);
  }

  @BeforeEach
  void configure() {
    CurriculumGraph graph = new CurriculumGraph(CV, "KAFKA", "v1", "PUBLISHED", List.of(
        new CurriculumGraph.SkillNode(
            UUID.randomUUID(), "KAFKA_BROKER", "Broker", "Description", "FOUNDATIONAL",
            new BigDecimal("0.8000"), 20, new BigDecimal("0.8000"), new BigDecimal("0.7500"), 5,
            List.of("QUIZ"), List.of("EASY"), 1,
            List.of(new CurriculumGraph.Objective("BROKER_OBJ", "Objective", true, 1)), List.of())));
    when(curriculumRepository.findReadableGraph("KAFKA", "v1")).thenReturn(Optional.of(graph));
    when(learnerRepository.provisionForSubject("user-1"))
        .thenReturn(new Learner(LEARNER_ONE, "user-1", "ACTIVE", NOW, NOW));
    when(learnerRepository.findBySubject("user-1"))
        .thenReturn(Optional.of(new Learner(LEARNER_ONE, "user-1", "ACTIVE", NOW, NOW)));
    when(learnerRepository.findBySubject("user-2"))
        .thenReturn(Optional.of(new Learner(LEARNER_TWO, "user-2", "ACTIVE", NOW, NOW)));
  }

  @Test
  void startRequiresLearnerRole() throws Exception {
    mockMvc.perform(post("/api/v1/me/learning-sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"domainCode\":\"KAFKA\",\"versionCode\":\"v1\"}")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void startCreatesNewSession() throws Exception {
    when(sessionRepository.findOpenSession(LEARNER_ONE, CV)).thenReturn(Optional.empty());
    when(sessionRepository.insertSession(eq(LEARNER_ONE), eq(CV), anyString()))
        .thenReturn(session(LearningSessionStatus.ACTIVE, 1));

    mockMvc.perform(post("/api/v1/me/learning-sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"domainCode\":\"KAFKA\",\"versionCode\":\"v1\"}")
            .with(learner("user-1")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void startResumesOpenSession() throws Exception {
    when(sessionRepository.findOpenSession(LEARNER_ONE, CV))
        .thenReturn(Optional.of(session(LearningSessionStatus.PAUSED, 2)));

    mockMvc.perform(post("/api/v1/me/learning-sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"domainCode\":\"KAFKA\",\"versionCode\":\"v1\"}")
            .with(learner("user-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAUSED"));
  }

  @Test
  void validTransitionAdvancesVersion() throws Exception {
    when(sessionRepository.findByIdAndLearner(SESSION, LEARNER_ONE))
        .thenReturn(Optional.of(session(LearningSessionStatus.ACTIVE, 1)),
            Optional.of(session(LearningSessionStatus.PAUSED, 2)));
    when(sessionRepository.applyTransition(eq(SESSION), eq(1), eq(LearningSessionStatus.PAUSED),
        eq(2), eq(LearningSessionCommand.PAUSE), anyString(), any())).thenReturn(true);

    mockMvc.perform(post("/api/v1/me/learning-sessions/" + SESSION + "/transitions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"command\":\"PAUSE\",\"expectedVersion\":1}")
            .with(learner("user-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAUSED"))
        .andExpect(jsonPath("$.version").value(2));
  }

  @Test
  void invalidTransitionIsUnprocessable() throws Exception {
    when(sessionRepository.findByIdAndLearner(SESSION, LEARNER_ONE))
        .thenReturn(Optional.of(session(LearningSessionStatus.PAUSED, 1)));

    mockMvc.perform(post("/api/v1/me/learning-sessions/" + SESSION + "/transitions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"command\":\"COMPLETE\",\"expectedVersion\":1}")
            .with(learner("user-1")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("INVALID_SESSION_TRANSITION"));
  }

  @Test
  void staleVersionConflicts() throws Exception {
    when(sessionRepository.findByIdAndLearner(SESSION, LEARNER_ONE))
        .thenReturn(Optional.of(session(LearningSessionStatus.ACTIVE, 3)));

    mockMvc.perform(post("/api/v1/me/learning-sessions/" + SESSION + "/transitions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"command\":\"PAUSE\",\"expectedVersion\":1}")
            .with(learner("user-1")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SESSION_CONFLICT"));
  }

  @Test
  void sessionOwnedByAnotherLearnerIsNotFound() throws Exception {
    when(sessionRepository.findByIdAndLearner(SESSION, LEARNER_TWO)).thenReturn(Optional.empty());

    mockMvc.perform(post("/api/v1/me/learning-sessions/" + SESSION + "/transitions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"command\":\"PAUSE\",\"expectedVersion\":1}")
            .with(learner("user-2")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
  }
}
