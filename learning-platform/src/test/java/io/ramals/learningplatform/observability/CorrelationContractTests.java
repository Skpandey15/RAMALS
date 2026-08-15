package io.ramals.learningplatform.observability;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:correlation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD="
})
@AutoConfigureMockMvc
@Import(CorrelationContractTests.FailureController.class)
class CorrelationContractTests {

  private static final String UUID_V7_PATTERN =
      "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

  @Autowired
  private MockMvc mockMvc;

  @Test
  void generatesAndEchoesInteractionIdForDatabaseRequest() throws Exception {
    mockMvc.perform(get("/api/v1/system/database-probe"))
        .andDo(result -> { })
        .andExpect(status().isUnauthorized());

    mockMvc.perform(get("/api/v1/system/database-probe")
            .with(jwt().authorities(() -> "ROLE_SERVICE")))
        .andExpect(status().isOk())
        .andExpect(header().string(CorrelationHeaders.INTERACTION_ID, matchesPattern(UUID_V7_PATTERN)))
        .andExpect(header().exists(CorrelationHeaders.REQUEST_ID))
        .andExpect(header().exists(CorrelationHeaders.TRACE_ID))
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void retryPreservesCallerInteractionId() throws Exception {
    String interactionId = UuidV7.generate().toString();

    mockMvc.perform(get("/api/v1/system/database-probe")
            .with(jwt().authorities(() -> "ROLE_SERVICE"))
            .header(CorrelationHeaders.INTERACTION_ID, interactionId))
        .andExpect(status().isOk())
        .andExpect(header().string(CorrelationHeaders.INTERACTION_ID, interactionId));

    mockMvc.perform(get("/api/v1/system/database-probe")
            .with(jwt().authorities(() -> "ROLE_SERVICE"))
            .header(CorrelationHeaders.INTERACTION_ID, interactionId))
        .andExpect(status().isOk())
        .andExpect(header().string(CorrelationHeaders.INTERACTION_ID, interactionId));
  }

  @Test
  void rejectsInvalidInteractionIdWithSafeProblemDetails() throws Exception {
    mockMvc.perform(get("/api/v1/system/database-probe")
            .header(CorrelationHeaders.INTERACTION_ID, "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string(CorrelationHeaders.INTERACTION_ID, matchesPattern(UUID_V7_PATTERN)))
        .andExpect(jsonPath("$.code").value("INVALID_INTERACTION_ID"))
        .andExpect(jsonPath("$.interactionId").value(matchesPattern(UUID_V7_PATTERN)))
        .andExpect(jsonPath("$.detail").value("X-Interaction-ID must be a canonical lowercase UUIDv7."));
  }

  @Test
  void databaseFailureReturnsSafeCorrelationProblem() throws Exception {
    String interactionId = UuidV7.generate().toString();

    mockMvc.perform(get("/test/db-failure")
            .with(jwt().authorities(() -> "ROLE_SERVICE"))
            .header(CorrelationHeaders.INTERACTION_ID, interactionId))
        .andExpect(status().isInternalServerError())
        .andExpect(header().string(CorrelationHeaders.INTERACTION_ID, interactionId))
        .andExpect(header().exists(CorrelationHeaders.TRACE_ID))
        .andExpect(jsonPath("$.code").value("DATABASE_OPERATION_FAILED"))
        .andExpect(jsonPath("$.interactionId").value(interactionId))
        .andExpect(jsonPath("$.traceId").isNotEmpty())
        .andExpect(jsonPath("$.detail").value("The operation could not be completed."));
  }

  @RestController
  static class FailureController {

    @GetMapping("/test/db-failure")
    void failDatabaseOperation() {
      throw new DataAccessResourceFailureException("sensitive database detail");
    }
  }
}
