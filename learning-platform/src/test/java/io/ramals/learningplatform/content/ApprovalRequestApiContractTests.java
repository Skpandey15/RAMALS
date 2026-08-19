package io.ramals.learningplatform.content;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:approval-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class ApprovalRequestApiContractTests {
  private static final UUID REQUEST = UUID.fromString("01900000-0000-7000-8000-000000000711");

  @Autowired MockMvc mockMvc;
  @MockitoBean ApprovalRequestService service;

  @Test
  void learnerCannotCreateApprovalRequest() throws Exception {
    mockMvc.perform(post("/api/v1/approval-requests")
        .header("Idempotency-Key", "create-1")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LEARNER")))
        .contentType("application/json")
        .content("{\"candidateId\":\"01900000-0000-7000-8000-000000000712\",\"candidateRevision\":1}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminWithoutMfaCannotApprove() throws Exception {
    mockMvc.perform(post("/api/v1/approval-requests/" + REQUEST + "/approve")
        .header("Idempotency-Key", "approve-1")
        .with(jwt().jwt(token -> token.subject("admin"))
            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminWithOtpMayApprove() throws Exception {
    when(service.approve(eq(REQUEST), eq("admin"), eq("approve-2"))).thenReturn(response());

    mockMvc.perform(post("/api/v1/approval-requests/" + REQUEST + "/approve")
        .header("Idempotency-Key", "approve-2")
        .with(jwt().jwt(token -> token.subject("admin").claim("amr", java.util.List.of("pwd", "otp")))
            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("APPROVED"));
  }

  @Test
  void contentAuthorMayApproveWithoutMfa() throws Exception {
    when(service.approve(eq(REQUEST), eq("author"), eq("approve-3"))).thenReturn(response());

    mockMvc.perform(post("/api/v1/approval-requests/" + REQUEST + "/approve")
        .header("Idempotency-Key", "approve-3")
        .with(jwt().jwt(token -> token.subject("author"))
            .authorities(new SimpleGrantedAuthority("ROLE_CONTENT_AUTHOR"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("APPROVED"));
  }

  private static ApprovalRequest response() {
    return new ApprovalRequest(REQUEST, UUID.randomUUID(), 1, ApprovalState.APPROVED, "{}",
        "a".repeat(64), "proposal", "1.0", "ASSESSMENT", "v1", "default", null, "prompt",
        "m1-t12-policy-v1", "spring-content-promotion-v1", "interaction", "creator",
        Instant.now(), Instant.now(), Instant.now().plusSeconds(100), "admin", Instant.now(), null,
        UUID.randomUUID());
  }
}
