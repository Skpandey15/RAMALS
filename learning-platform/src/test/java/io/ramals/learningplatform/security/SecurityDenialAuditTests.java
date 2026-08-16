package io.ramals.learningplatform.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Master Plan §7 and §8: a security denial must return the correlation ids to the caller and leave
 * a durable audit record.
 *
 * <p>Before this, Spring Security's default handlers returned 401 with an empty body — a learner
 * hitting an auth failure had no support code to quote — and denials survived only in the
 * application log, which has a retention horizon and no immutability guarantee.
 */
@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:security-denial-audit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class SecurityDenialAuditTests {

  private static final String INTERACTION_ID = "01920000-0000-7000-8000-0000000000d1";

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  SecurityAuditRepository securityAuditRepository;

  @Test
  void unauthenticatedDenialCarriesCorrelationIdsInTheBody() throws Exception {
    mockMvc.perform(get("/api/v1/me").header("X-Interaction-ID", INTERACTION_ID))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("X-Interaction-ID", INTERACTION_ID))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.interactionId").value(INTERACTION_ID))
        .andExpect(jsonPath("$.traceId").exists());
  }

  @Test
  void unauthenticatedDenialIsAudited() throws Exception {
    mockMvc.perform(get("/api/v1/me").header("X-Interaction-ID", INTERACTION_ID))
        .andExpect(status().isUnauthorized());

    verify(securityAuditRepository).append(
        eq("AUTHENTICATION_FAILED"), eq("DENIED"), any(), eq("GET"), any(), eq(401),
        eq("AUTHENTICATION_REQUIRED"), any(), eq(INTERACTION_ID), any());
  }

  @Test
  void roleDenialIsAuditedAsAuthorizationFailure() throws Exception {
    mockMvc.perform(get("/api/v1/admin/curricula")
            .header("X-Interaction-ID", INTERACTION_ID)
            .with(jwt().authorities(new SimpleGrantedAuthority("LEARNER"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        .andExpect(jsonPath("$.interactionId").value(INTERACTION_ID));

    verify(securityAuditRepository).append(
        eq("AUTHORIZATION_DENIED"), eq("DENIED"), any(), eq("GET"), any(), eq(403),
        eq("ACCESS_DENIED"), any(), eq(INTERACTION_ID), any());
  }

  @Test
  void deniedResponseNeverDisclosesWhichPolicyMatched() throws Exception {
    // The caller learns that access was refused, never which role was missing or whether the
    // resource exists — that would turn an error surface into an enumeration oracle.
    ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);

    String body = mockMvc.perform(get("/api/v1/admin/curricula")
            .header("X-Interaction-ID", INTERACTION_ID)
            .with(jwt().authorities(new SimpleGrantedAuthority("LEARNER"))))
        .andExpect(status().isForbidden())
        .andReturn().getResponse().getContentAsString();

    verify(securityAuditRepository).append(
        any(), any(), any(), any(), any(), any(), any(), detail.capture(), any(), any());

    org.assertj.core.api.Assertions.assertThat(body)
        .doesNotContain("ADMIN").doesNotContain("ROLE_").doesNotContain("hasRole");
    org.assertj.core.api.Assertions.assertThat(detail.getValue()).isNull();
  }

  @Test
  void auditFailureNeverTurnsADenialIntoAServerError() throws Exception {
    // Losing the audit sink must not convert a correct 403 into a 500. The security decision
    // stands; the failure to record it is logged.
    doThrow(new IllegalStateException("audit sink unavailable"))
        .when(securityAuditRepository).append(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

    mockMvc.perform(get("/api/v1/admin/curricula")
            .header("X-Interaction-ID", INTERACTION_ID)
            .with(jwt().authorities(new SimpleGrantedAuthority("LEARNER"))))
        .andExpect(status().isForbidden());
  }
}
