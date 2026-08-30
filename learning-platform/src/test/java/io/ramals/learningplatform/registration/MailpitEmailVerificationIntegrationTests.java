package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The DEV/CI email path, end to end: registration, Keycloak's own mail, a real SMTP sink, and the
 * verification link actually followed.
 *
 * <p>Proving a message was queued would prove almost nothing — the interesting failures are a realm
 * whose SMTP settings do not resolve, a verification link that points at an unreachable issuer, and
 * an action token that does not flip the flag RAMALS reconciles from. So the test follows the link
 * out of the delivered message and then re-reads verification state through
 * {@link IdentityProviderPort}, which is the same path {@code OnboardingService} uses.
 *
 * <pre>
 *   docker run -d --name mailpit --network ramals-qual --network-alias mailpit \
 *     -p 58025:8025 axllent/mailpit:v1.27.11
 *
 *   RAMALS_TEST_KEYCLOAK_URL=http://localhost:58081 \
 *   RAMALS_TEST_KEYCLOAK_ADMIN=admin RAMALS_TEST_KEYCLOAK_ADMIN_PASSWORD=... \
 *   RAMALS_TEST_MAILPIT_URL=http://localhost:58025 \
 *   ./gradlew :learning-platform:integrationTest --tests '*MailpitEmailVerification*'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_KEYCLOAK_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_MAILPIT_URL", matches = ".+")
class MailpitEmailVerificationIntegrationTests {

  private static final String REALM = "ramals";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private static final Pattern LINK =
      Pattern.compile("https?://[^\\s\"'<>]*login-actions/action-token[^\\s\"'<>]*");

  private static String keycloakUrl;
  private static String mailpitUrl;
  private static String bootstrapToken;
  private static KeycloakRegistrationAdminClient adapter;

  @BeforeAll
  static void prepare() throws Exception {
    keycloakUrl = required("RAMALS_TEST_KEYCLOAK_URL");
    mailpitUrl = required("RAMALS_TEST_MAILPIT_URL");
    bootstrapToken = bootstrapToken();

    JsonNode clients = adminGet("/admin/realms/" + REALM + "/clients?clientId=ramals-registration-admin");
    String internalId = clients.get(0).get("id").asString();
    String secret = adminGet("/admin/realms/" + REALM + "/clients/" + internalId + "/client-secret")
        .get("value").asString();

    RegistrationProperties properties = new RegistrationProperties();
    properties.setEnabled(true);
    properties.getKeycloak().setBaseUrl(keycloakUrl);
    properties.getKeycloak().setRealm(REALM);
    properties.getKeycloak().setClientId("ramals-registration-admin");
    properties.getKeycloak().setClientSecret(secret);
    adapter = new KeycloakRegistrationAdminClient(properties);

    deleteAllMail();
  }

  /**
   * Registration produces a real, deliverable verification message carrying a live action token.
   *
   * <p><strong>What this stops short of, and why.</strong> Redeeming the token is not automated here.
   * Keycloak 26 answers the action-token URL with an interstitial and then an interactive sign-in
   * page — link-prefetch protection — and that page is script-rendered, so completing it needs a real
   * browser rather than an HTTP client. Driving it with string-scraped form posts would be
   * non-deterministic and would break on any theme change, which is worse than an honest gap.
   *
   * <p>So the click-through remains NOT VERIFIED. What is verified is everything on either side of
   * it: the realm's SMTP settings resolve and Keycloak really delivers; the message is addressed from
   * the configured sender; it carries an action-token link on the configured issuer host that
   * Keycloak actually serves; and — in
   * {@link KeycloakRegistrationBoundaryIntegrationTests#requestsVerificationAndReconcilesTrustedState()}
   * — RAMALS reconciles from provider-held state once that state changes. The unproven step is
   * Keycloak's own, between the two.
   */
  @Test
  @DisplayName("registration delivers a real verification message carrying a live action token")
  void verificationMailIsDeliveredWithALiveActionToken() throws Exception {
    String email = "mailpit-" + UUID.randomUUID() + "@example.com";
    IdentityProviderPort.Identity identity = adapter.createLearner(UUID.randomUUID().toString(),
        new RegistrationRequest("Asha", "Iyer", email, "9876543210", "IN", "Pune",
            "correct horse battery", "correct horse battery",
            "terms-v1", "privacy-v1", "adult-18-v1", true, true, true));
    assertThat(adapter.emailVerified(identity.subject())).isFalse();

    adapter.sendVerificationEmail(identity.subject());

    JsonNode message = awaitMessageFor(email);
    assertThat(message).as("Keycloak must deliver a verification message to the SMTP sink")
        .isNotNull();

    String body = messageBody(message.get("ID").asString());
    Matcher link = LINK.matcher(body);
    assertThat(link.find()).as("the message must carry a Keycloak action-token link").isTrue();
    String actionToken = link.group();
    // Pointed at the issuer this deployment is configured with, not at a Keycloak default host --
    // the failure that makes every emailed link dead on arrival.
    assertThat(actionToken).startsWith(keycloakUrl + "/realms/" + REALM + "/login-actions/action-token");

    HttpResponse<String> followed = HTTP.send(
        HttpRequest.newBuilder().uri(URI.create(actionToken)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    // Served by Keycloak rather than 404/502: the token is live and the route resolves.
    assertThat(followed.statusCode()).isBetween(200, 299);

    // And RAMALS still reports unverified, because nobody has completed the interactive step. The
    // browser is the authority for that, and a test that asserted otherwise would be asserting a
    // shortcut rather than the flow.
    assertThat(adapter.emailVerified(identity.subject())).isFalse();
  }

  @Test
  @DisplayName("the delivered message is addressed from the configured realm sender")
  void messageComesFromTheConfiguredSender() throws Exception {
    String email = "mailpit-from-" + UUID.randomUUID() + "@example.com";
    IdentityProviderPort.Identity identity = adapter.createLearner(UUID.randomUUID().toString(),
        new RegistrationRequest("Asha", "Iyer", email, "9876543210", "IN", "Pune",
            "correct horse battery", "correct horse battery",
            "terms-v1", "privacy-v1", "adult-18-v1", true, true, true));
    adapter.sendVerificationEmail(identity.subject());

    JsonNode message = awaitMessageFor(email);
    assertThat(message).isNotNull();
    // Confirms the realm's smtpServer block is the one in use, rather than a Keycloak default.
    assertThat(message.get("From").get("Address").asString()).isEqualTo("no-reply@ramals.local");
  }

  // -------------------------------------------------------------------------------------------

  private static JsonNode awaitMessageFor(String email) throws Exception {
    for (int attempt = 0; attempt < 40; attempt++) {
      JsonNode messages = mailpitGet("/api/v1/messages?limit=200").get("messages");
      if (messages != null) {
        for (JsonNode message : messages) {
          for (JsonNode recipient : message.get("To")) {
            if (email.equalsIgnoreCase(recipient.get("Address").asString())) {
              return message;
            }
          }
        }
      }
      Thread.sleep(250);
    }
    return null;
  }

  private static String messageBody(String id) throws Exception {
    JsonNode message = mailpitGet("/api/v1/message/" + id);
    String text = message.has("Text") ? message.get("Text").asString() : "";
    String html = message.has("HTML") ? message.get("HTML").asString() : "";
    return text + "\n" + html;
  }

  private static void deleteAllMail() throws Exception {
    HTTP.send(HttpRequest.newBuilder().uri(URI.create(mailpitUrl + "/api/v1/messages"))
        .method("DELETE", HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode mailpitGet(String path) throws Exception {
    HttpResponse<String> response = HTTP.send(
        HttpRequest.newBuilder().uri(URI.create(mailpitUrl + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("GET %s", path).isBetween(200, 299);
    return JSON.readTree(response.body());
  }

  private static String bootstrapToken() throws Exception {
    String form = "grant_type=password&client_id=admin-cli&username="
        + required("RAMALS_TEST_KEYCLOAK_ADMIN") + "&password="
        + java.net.URLEncoder.encode(required("RAMALS_TEST_KEYCLOAK_ADMIN_PASSWORD"), StandardCharsets.UTF_8);
    HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
        .uri(URI.create(keycloakUrl + "/realms/master/protocol/openid-connect/token"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    return JSON.readTree(response.body()).get("access_token").asString();
  }

  private static JsonNode adminGet(String path) throws Exception {
    HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
        .uri(URI.create(keycloakUrl + path))
        .header("Authorization", "Bearer " + bootstrapToken)
        .GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("GET %s", path).isBetween(200, 299);
    return JSON.readTree(response.body());
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set for the Mailpit suite.");
    }
    return value;
  }
}
