package io.ramals.learningplatform.observability;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ramals.learningplatform.learner.LearnerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:observability;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class ObservabilityApiTests {

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  LearnerRepository learnerRepository;

  private static org.springframework.test.web.servlet.request.RequestPostProcessor learner() {
    return jwt().jwt(token -> token.subject("user-1"))
        .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"));
  }

  @Test
  void prometheusEndpointIsProtected() throws Exception {
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  void forcedFailuresAreCountedAndCarryASupportCode() throws Exception {
    // Service-layer failure: bean validation is rejected deterministically as VALIDATION_FAILED.
    mockMvc.perform(put("/api/v1/me/goal").with(learner())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetDomainCode\":\"not a code\",\"targetProficiency\":2}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(header().exists("X-Interaction-ID"))
        .andExpect(jsonPath("$.interactionId").isNotEmpty());

    // Database-layer failure surfaces as a generic 500 that still carries the support code.
    org.mockito.Mockito.when(learnerRepository.findBySubject("user-1"))
        .thenThrow(new DataAccessResourceFailureException("SELECT secret FROM core.learner failed"));
    mockMvc.perform(get("/api/v1/me/recommendations").with(learner()))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("DATABASE_OPERATION_FAILED"))
        .andExpect(jsonPath("$.interactionId").isNotEmpty());

    String scrape = mockMvc.perform(get("/actuator/prometheus").with(learner()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    org.assertj.core.api.Assertions.assertThat(scrape)
        .contains("ramals_api_errors")
        .contains("VALIDATION_FAILED")
        .contains("DATABASE_OPERATION_FAILED")
        .contains("http_server_requests");
  }

  @Test
  void databaseFailureResponseIsRedacted() throws Exception {
    org.mockito.Mockito.when(learnerRepository.findBySubject("user-1"))
        .thenThrow(new DataAccessResourceFailureException("SELECT secret_token FROM core.learner"));

    mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/me/recommendations").with(learner()))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("The operation could not be completed."))
        .andExpect(content().string(not(containsString("SELECT"))))
        .andExpect(content().string(not(containsString("secret_token"))))
        .andExpect(content().string(not(containsString("core.learner"))));
  }
}
