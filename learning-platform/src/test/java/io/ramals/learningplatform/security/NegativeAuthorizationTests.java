package io.ramals.learningplatform.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ramals.learningplatform.assessment.AssessmentAttempt;
import io.ramals.learningplatform.assessment.AssessmentRepository;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Negative authorization suite. Verifies that valid identity alone is not enough: unauthenticated
 * requests are rejected, roles are enforced per endpoint, and object-level ownership prevents
 * cross-learner access (IDOR/BOLA).
 */
@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:negative-authz;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class NegativeAuthorizationTests {

  private static final UUID LEARNER_ONE = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID LEARNER_TWO = UUID.fromString("01900000-0000-7000-8000-0000000000a2");
  private static final UUID VERSION = UUID.fromString("01900000-0000-7000-8000-000000000402");
  private static final UUID ATTEMPT = UUID.fromString("01900000-0000-7000-8000-0000000004f1");
  private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  AssessmentRepository assessmentRepository;

  @MockitoBean
  LearnerRepository learnerRepository;

  private static SimpleGrantedAuthority role(String role) {
    return new SimpleGrantedAuthority(role);
  }

  @Test
  void unauthenticatedRequestsAreRejected() throws Exception {
    mockMvc.perform(get("/api/v1/me/profile")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/admin/curricula")).andExpect(status().isUnauthorized());
  }

  @Test
  void rolesAreEnforcedPerEndpoint() throws Exception {
    // a learner cannot reach administrative content operations
    mockMvc.perform(get("/api/v1/admin/curricula").with(jwt().authorities(role("ROLE_LEARNER"))))
        .andExpect(status().isForbidden());
    // a non-learner cannot reach learner self-service
    mockMvc.perform(get("/api/v1/me/profile").with(jwt().authorities(role("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/v1/me/recommendations").with(jwt().authorities(role("ROLE_CONTENT_AUTHOR"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void oneLearnerCannotReadAnotherLearnersAttempt() throws Exception {
    when(learnerRepository.findBySubject("user-2"))
        .thenReturn(Optional.of(new Learner(LEARNER_TWO, "user-2", "ACTIVE", NOW, NOW)));
    when(assessmentRepository.findAttempt(ATTEMPT)).thenReturn(Optional.of(
        new AssessmentAttempt(ATTEMPT, LEARNER_ONE, VERSION, "IN_PROGRESS", "key-1", NOW, NOW)));

    mockMvc.perform(get("/api/v1/diagnostics/KAFKA/attempts/" + ATTEMPT)
            .with(jwt().jwt(token -> token.subject("user-2")).authorities(role("ROLE_LEARNER"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FOUND"));
  }
}
