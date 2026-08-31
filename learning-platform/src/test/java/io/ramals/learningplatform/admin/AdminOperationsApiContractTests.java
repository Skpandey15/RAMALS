package io.ramals.learningplatform.admin;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:admin-operations-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class AdminOperationsApiContractTests {

  private static final UUID LEARNER = UUID.fromString("01900000-0000-7000-8000-000000000111");

  @Autowired MockMvc mockMvc;
  @MockitoBean AdminLearnerService learnerService;
  @MockitoBean AdminOperationsService operationsService;
  @MockitoBean AdminAuditService auditService;
  @MockitoBean AdminIdentityService identityService;

  @Test
  void allAdministrativeReadSurfacesRejectLearnerRole() throws Exception {
    var learner = jwt().authorities(new SimpleGrantedAuthority("ROLE_LEARNER"));
    mockMvc.perform(get("/api/v1/admin/learners").with(learner)).andExpect(status().isForbidden());
    mockMvc.perform(get("/api/v1/admin/operations/snapshot").with(learner)).andExpect(status().isForbidden());
    mockMvc.perform(get("/api/v1/admin/audit/security").with(learner)).andExpect(status().isForbidden());
    mockMvc.perform(get("/api/v1/admin/identities").with(learner)).andExpect(status().isForbidden());
  }

  @Test
  void adminCanReadOperationalSurfaces() throws Exception {
    when(operationsService.snapshot()).thenReturn(new AdminOperationalSnapshot(
        4L, 3L, 1L, 0L, 2L, 1L, 2L, 1L, 5L, 6L));
    when(identityService.listUsers()).thenReturn(List.of(
        new AdminIdentityUser("user-2", "staff", "staff@example.test", true,
            Set.of("CONTENT_AUTHOR"))));
    when(auditService.recentSecurityActivity(anyInt())).thenReturn(List.of());

    var admin = jwt().jwt(token -> token.subject("admin-1"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    mockMvc.perform(get("/api/v1/admin/operations/snapshot").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.learnersTotal").value(4))
        .andExpect(jsonPath("$.authorizationDenials24h").value(5));
    mockMvc.perform(get("/api/v1/admin/identities").with(admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].username").value("staff"));
    mockMvc.perform(get("/api/v1/admin/audit/security").with(admin))
        .andExpect(status().isOk());
  }

  @Test
  void learnerStatusMutationRequiresAdminMfa() throws Exception {
    var adminWithoutMfa = jwt().jwt(token -> token.subject("admin-1"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    mockMvc.perform(patch("/api/v1/admin/learners/" + LEARNER + "/status")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SUSPENDED\"}")
            .with(adminWithoutMfa))
        .andExpect(status().isForbidden());

    AdminLearnerSummary suspended = new AdminLearnerSummary(
        LEARNER, "learner-subject", "SUSPENDED", "Ada", "Learner", "ada@example.test",
        "+919999999999", "IN", "Bengaluru", true, true, "ONBOARDED",
        Instant.parse("2026-08-30T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"));
    when(learnerService.changeStatus("admin-1", LEARNER, "SUSPENDED")).thenReturn(suspended);

    var adminWithMfa = jwt().jwt(token -> token.subject("admin-1").claim("amr", List.of("pwd", "otp")))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    mockMvc.perform(patch("/api/v1/admin/learners/" + LEARNER + "/status")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SUSPENDED\"}")
            .with(adminWithMfa))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"));
  }

  @Test
  void identityRoleMutationRequiresAdminMfa() throws Exception {
    var adminWithoutMfa = jwt().jwt(token -> token.subject("admin-1"))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    mockMvc.perform(post("/api/v1/admin/identities/user-2/roles/CONTENT_AUTHOR")
            .with(adminWithoutMfa))
        .andExpect(status().isForbidden());

    AdminIdentityUser updated = new AdminIdentityUser(
        "user-2", "staff", "staff@example.test", true, Set.of("CONTENT_AUTHOR"));
    when(identityService.addRole("admin-1", "user-2", "CONTENT_AUTHOR")).thenReturn(updated);

    var adminWithMfa = jwt().jwt(token -> token.subject("admin-1").claim("amr", List.of("pwd", "otp")))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    mockMvc.perform(post("/api/v1/admin/identities/user-2/roles/CONTENT_AUTHOR")
            .with(adminWithMfa))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.realmRoles[0]").value("CONTENT_AUTHOR"));
  }
}
