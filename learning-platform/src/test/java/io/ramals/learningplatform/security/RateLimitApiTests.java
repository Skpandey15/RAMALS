package io.ramals.learningplatform.security;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:rate-limit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false",
    "ramals.security.rate-limit.capacity=3",
    "ramals.security.rate-limit.refill-per-second=0"
})
@AutoConfigureMockMvc
class RateLimitApiTests {

  @Autowired
  MockMvc mockMvc;

  @Test
  void burstBeyondCapacityIsThrottledWithProblemDetails() throws Exception {
    // The first three requests consume the bucket (each still 401 for lack of a token).
    for (int attempt = 0; attempt < 3; attempt++) {
      mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }
    // The fourth is shed before authentication with a 429 and a retry hint.
    mockMvc.perform(get("/api/v1/me"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }
}
