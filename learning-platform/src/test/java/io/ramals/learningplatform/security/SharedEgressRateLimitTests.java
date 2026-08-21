package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The shared-egress case, at the limits a deployment actually ships with.
 *
 * <p>{@link SubjectRateLimitApiTests} proves the same mechanism with synthetic values — an IP tier
 * set to 10,000 so the subject tier is the one under test. That is the right way to test the
 * mechanism and the wrong way to catch this defect: it pins the IP tier to a figure no deployment
 * uses, so it passed happily throughout the period when the shipped IP tier was 120/60 and a class
 * of thirty learners would throttle each other.
 *
 * <p>So this class overrides no rate-limit property at all. It runs against whatever
 * application.yml ships, which is the only configuration that can answer the question TD-R1-01
 * asks: does a school behind one NAT still work?
 */
@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:shared-egress;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
    // Deliberately no ramals.security.rate-limit.* here. The shipped defaults are the subject.
})
@AutoConfigureMockMvc
class SharedEgressRateLimitTests {

  /** Well under the per-learner allowance of 120, and far beyond it once summed across learners. */
  private static final int LEARNERS = 30;
  private static final int REQUESTS_EACH = 10;

  @Autowired
  MockMvc mockMvc;

  private static MockHttpServletRequestBuilder asLearner(String subject) {
    // MockMvc gives every request the same remote address, so all of these share one IP bucket —
    // which is exactly the school-behind-a-NAT arrangement being tested.
    return get("/api/v1/me").with(jwt().jwt(token -> token.subject(subject)));
  }

  @Test
  @DisplayName("thirty learners behind one address are not throttled at the shipped defaults")
  void aClassroomShareOneAddressWithoutThrottlingEachOther() throws Exception {
    // 300 requests from one address. Under the defaults that shipped before TD-R1-01 — an IP tier
    // of capacity 120, refill 60/s — this could not pass: the bucket is spent after 120 and the
    // rest are refused regardless of how little any individual learner asked for. Under 600/300 it
    // passes, and no learner comes close to their own 120.
    for (int request = 0; request < REQUESTS_EACH; request++) {
      for (int learner = 0; learner < LEARNERS; learner++) {
        mockMvc
            .perform(asLearner("classroom-learner-%02d".formatted(learner)))
            .andExpect(status().isOk());
      }
    }
  }

  @Test
  @DisplayName("one learner over their own share is still throttled, and the rest are unaffected")
  void anIndividualIsStillSubjectLimited() throws Exception {
    // The other half of the contract: raising the shared ceiling must not remove per-user fairness.
    //
    // Sent as a loop-until-refused rather than exactly 121 requests. The bucket refills while the
    // test runs — at 60/s the 121st request is not reliably the one that fails, and asserting that
    // it is produces a test that passes on a slow machine and fails on a fast one. What is actually
    // guaranteed is that a caller outpacing 60/s is refused before long, so that is what is
    // asserted. The cap is a bound on the loop, not an expected value.
    String heavy = "classroom-learner-heavy";
    MockHttpServletResponse refusal = null;
    int refusedAfter = -1;
    for (int request = 1; request <= 400 && refusal == null; request++) {
      MockHttpServletResponse response =
          mockMvc.perform(asLearner(heavy)).andReturn().getResponse();
      if (response.getStatus() == 429) {
        refusal = response;
        refusedAfter = request;
      }
    }

    assertThat(refusedAfter)
        .as("a learner sending far faster than their 60/s share must eventually be refused")
        .isGreaterThan(0);

    // Asserted on the refusal the loop actually saw, not on a fresh request. The bucket refills at
    // 60/s, so by the time another request is issued a token is often back and the answer is 200 --
    // re-asking is a race, and the first version of this test lost it every time.
    assertThat(refusal.getHeader("Retry-After"))
        .as("a throttled learner needs to be told when to come back")
        .isNotNull();
    assertThat(refusal.getContentAsString())
        .as("the 429 body must stay a Problem Details document with a quotable support code")
        .contains("RATE_LIMITED");

    // It must be the subject tier that refused, not the shared one. The shared bucket is five times
    // larger and refills five times faster, so a single learner reaches their own limit first --
    // and the proof is that everyone else is still served from the same address.
    mockMvc
        .perform(asLearner("classroom-learner-neighbour"))
        .andExpect(status().isOk());
  }
}
