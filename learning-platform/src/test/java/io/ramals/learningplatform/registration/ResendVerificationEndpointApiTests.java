package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The resend route as a client actually reaches it.
 *
 * <p>Unit tests cover the service's indistinguishability; this asks the questions only the wired
 * application can answer. Chiefly: is the route reachable <em>without a token</em>? It has to be —
 * a learner needing it cannot log in, because the missing mail is what blocks logging in — and a
 * route permitted in {@code SecurityConfig} but never exercised unauthenticated is exactly the kind
 * of thing that passes every unit test and returns 401 in production.
 *
 * <p>The second question is whether the HTTP layer preserves the property the service establishes.
 * A body that is identical between a real send and a no-op is no use if the status code, or a
 * validation error, or a rate-limit rejection differs by whether the address exists.
 */
@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:resend-verification;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false",
    // Registration must be genuinely on: disabled, the route refuses before any of this is
    // reachable. RegistrationConfiguration validates the whole capability at startup, so the
    // baseline below is what a running deployment actually requires -- none of it is a credential.
    "ramals.registration.enabled=true",
    "ramals.registration.environment=dev",
    "ramals.registration.sms.provider=fake",
    "ramals.registration.keycloak.base-url=http://keycloak:8080",
    "ramals.registration.keycloak.realm=ramals",
    "ramals.registration.keycloak.client-id=ramals-registration-admin",
    "ramals.registration.keycloak.client-secret=test-secret",
    "ramals.registration.keycloak.verification-client-id=ramals-web-ui",
    "ramals.registration.keycloak.verification-redirect-uri=http://localhost:8080/",
    "ramals.registration.consent.terms-version=terms-v1",
    "ramals.registration.consent.terms-ref=terms/v1",
    "ramals.registration.consent.privacy-version=privacy-v1",
    "ramals.registration.consent.privacy-ref=privacy/v1",
    "ramals.registration.consent.adult-statement-version=adult-18-v1",
    // 32 bytes of test material, Base64. Only its shape matters; nothing signs anything real here.
    "ramals.registration.otp.hmac-key-ring=v1:BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
})
@AutoConfigureMockMvc
class ResendVerificationEndpointApiTests {

  private static final String PATH = "/api/v1/registration/verification/resend";
  private static final String KNOWN = """
      {"email":"known@example.com"}""";
  private static final String UNKNOWN = """
      {"email":"unknown@example.com"}""";

  @Autowired
  MockMvc mockMvc;

  /** The port, so the controller, the service and the response contract all stay under test. */
  @MockitoBean
  IdentityProviderPort identities;

  @MockitoBean
  AbuseCeiling ceilings;

  @MockitoBean
  RegistrationRepository registrations;

  @BeforeEach
  void allowQuota() {
    when(ceilings.consume(anyString(), anyInt(), anyInt())).thenReturn(true);
  }

  @Test
  @DisplayName("the route is reachable without a bearer token")
  void reachableUnauthenticated() throws Exception {
    when(identities.unverifiedSubjectForEmail(anyString())).thenReturn(Optional.of("subject-1"));

    mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(KNOWN))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("EMAIL_VERIFICATION"));

    // Enqueued, not sent: the provider call is the worker's job precisely so that its duration is
    // not part of the response the caller can time.
    verify(registrations).enqueueVerificationResend(any(), eq("subject-1"), any());
    verify(identities, never()).sendVerificationEmail(anyString());
  }

  @Test
  @DisplayName("a known and an unknown address produce byte-identical responses")
  void knownAndUnknownAreIndistinguishableOverHttp() throws Exception {
    when(identities.unverifiedSubjectForEmail("known@example.com"))
        .thenReturn(Optional.of("subject-1"));
    when(identities.unverifiedSubjectForEmail("unknown@example.com")).thenReturn(Optional.empty());

    var known = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(KNOWN))
        .andReturn().getResponse();
    var unknown = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(UNKNOWN))
        .andReturn().getResponse();

    // Status and body both. Either one varying is a usable oracle for whether an account exists.
    assertThat(known.getStatus()).isEqualTo(unknown.getStatus());
    assertThat(known.getContentAsString())
        .as("the response must not reveal whether the address is registered")
        .isEqualTo(unknown.getContentAsString());
  }

  @Test
  @DisplayName("a malformed address is rejected before any provider lookup")
  void malformedAddressNeverReachesTheProvider() throws Exception {
    mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"not-an-address"}"""))
        .andExpect(status().isBadRequest());

    // Validation is also a cheap shield: an address that could never have registered must not cost
    // a provider round trip, or the route becomes a way to drive load through Keycloak.
    verify(identities, never()).unverifiedSubjectForEmail(anyString());
    verify(identities, never()).sendVerificationEmail(anyString());
  }

  @Test
  @DisplayName("GET is not a resend")
  void onlyPostIsPermitted() throws Exception {
    // permitAll is declared for POST alone. A GET reaching the handler would make the address a
    // query parameter, and query strings are logged by proxies in a way request bodies are not.
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(PATH))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
  }
}
