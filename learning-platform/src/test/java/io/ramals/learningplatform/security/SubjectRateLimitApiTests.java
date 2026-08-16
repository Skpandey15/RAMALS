package io.ramals.learningplatform.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * R9: rate limiting is keyed on the authenticated subject, not the client IP.
 *
 * <p>The IP tier remains as a pre-authentication anti-flood ceiling. It is set generously here so
 * these tests exercise the subject tier — which is exactly the production intent, since many
 * legitimate learners share one egress IP behind a school, office or carrier NAT.
 */
@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:subject-rate-limit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false",
    "ramals.security.rate-limit.capacity=10000",
    "ramals.security.rate-limit.refill-per-second=10000",
    "ramals.security.rate-limit.subject.capacity=3",
    "ramals.security.rate-limit.subject.refill-per-second=0"
})
@AutoConfigureMockMvc
class SubjectRateLimitApiTests {

  @Autowired
  MockMvc mockMvc;

  private static MockHttpServletRequestBuilder asLearner(String subject) {
    return get("/api/v1/me").with(jwt().jwt(token -> token.subject(subject)));
  }

  @Test
  void aLearnerExhaustingTheirShareIsThrottled() throws Exception {
    for (int attempt = 0; attempt < 3; attempt++) {
      mockMvc.perform(asLearner("learner-heavy")).andExpect(status().isOk());
    }
    mockMvc.perform(asLearner("learner-heavy"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void oneLearnerCannotThrottleAnotherFromTheSameAddress() throws Exception {
    // The defect R9 recorded: every user behind a shared egress IP drew on one bucket, so a single
    // heavy user could throttle an entire school or office. Both learners here share a source
    // address; only the one over their own share is shed.
    for (int attempt = 0; attempt < 4; attempt++) {
      mockMvc.perform(asLearner("learner-noisy"));
    }
    mockMvc.perform(asLearner("learner-noisy")).andExpect(status().isTooManyRequests());

    mockMvc.perform(asLearner("learner-quiet")).andExpect(status().isOk());
  }

  @Test
  void unauthenticatedTrafficCannotDrainALearnersBucket() throws Exception {
    // The subject tier sits after token validation, so a request that never authenticates cannot
    // reach it with an attacker-chosen `sub`. This is what stops a caller from exhausting a victim's
    // allowance by borrowing their subject, or minting unlimited fresh buckets by varying the claim.
    // Anonymous floods are the IP tier's job, not this one's.
    for (int attempt = 0; attempt < 10; attempt++) {
      mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    // A learner's own allowance is untouched by that traffic.
    for (int attempt = 0; attempt < 3; attempt++) {
      mockMvc.perform(asLearner("learner-untouched")).andExpect(status().isOk());
    }
    mockMvc.perform(asLearner("learner-untouched")).andExpect(status().isTooManyRequests());
  }
}
