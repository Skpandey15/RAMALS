package io.ramals.learningplatform.admin;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:admin-content-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class AdminContentApiContractTests {

  private static final UUID VERSION = UUID.fromString("01900000-0000-7000-8000-000000000002");

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  ContentAdminService service;

  private static CurriculumVersionSummary summary(String status) {
    return new CurriculumVersionSummary(VERSION, "KAFKA", "v1", status, Instant.parse("2026-08-15T00:00:00Z"));
  }

  @Test
  void curriculaListingRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/admin/curricula")).andExpect(status().isUnauthorized());
  }

  @Test
  void learnerRoleIsDeniedAdministration() throws Exception {
    mockMvc.perform(get("/api/v1/admin/curricula")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/v1/admin/curricula/" + VERSION + "/publish")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void contentAuthorListsCurricula() throws Exception {
    when(service.listCurricula()).thenReturn(List.of(summary("PUBLISHED")));

    mockMvc.perform(get("/api/v1/admin/curricula")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTENT_AUTHOR"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].domainCode").value("KAFKA"))
        .andExpect(jsonPath("$[0].status").value("PUBLISHED"));
  }

  @Test
  void adminRetiresAPublishedVersion() throws Exception {
    when(service.retireCurriculum(eq("admin-1"), eq(VERSION.toString()))).thenReturn(summary("RETIRED"));

    mockMvc.perform(post("/api/v1/admin/curricula/" + VERSION + "/retire")
            .with(jwt().jwt(token -> token.subject("admin-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RETIRED"));
  }
}
