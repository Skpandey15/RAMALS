package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The registration adapter against a real Keycloak.
 *
 * <p>{@link IdentityProviderPort} is deliberately <em>not</em> substituted here. Everything the unit
 * suite proves about this adapter it proves against a double, which cannot show that the admin
 * client's grant is sufficient, that the role mapping lands, that the password policy is enforced, or
 * that a duplicate is reported as a duplicate. Those are properties of the boundary, not of our code.
 *
 * <p>Run with a Keycloak carrying the repository realm:
 *
 * <pre>
 *   docker build -f infrastructure/docker/keycloak/Dockerfile -t ramals-keycloak:local .
 *   docker run -d --name kc --network ramals-qual -p 58081:8080 \
 *     -e KC_DB=postgres -e KC_DB_URL=jdbc:postgresql://qual-pg:5432/keycloak \
 *     -e KC_DB_USERNAME=... -e KC_DB_PASSWORD=... \
 *     -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=... \
 *     -e KC_HEALTH_ENABLED=true -e KC_HTTP_ENABLED=true -e KC_HOSTNAME_STRICT=false \
 *     ramals-keycloak:local start-dev --import-realm
 *
 *   RAMALS_TEST_KEYCLOAK_URL=http://localhost:58081 \
 *   RAMALS_TEST_KEYCLOAK_ADMIN=admin \
 *   RAMALS_TEST_KEYCLOAK_ADMIN_PASSWORD=... \
 *   ./gradlew :learning-platform:integrationTest --tests '*KeycloakRegistrationBoundary*'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_KEYCLOAK_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAMALS_TEST_KEYCLOAK_ADMIN_PASSWORD", matches = ".+")
class KeycloakRegistrationBoundaryIntegrationTests {

  private static final String REALM = "ramals";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static String baseUrl;
  private static String bootstrapToken;
  private static KeycloakRegistrationAdminClient adapter;

  @BeforeAll
  static void resolveAdminClientSecret() throws Exception {
    baseUrl = required("RAMALS_TEST_KEYCLOAK_URL");
    bootstrapToken = bootstrapToken();

    // The realm ships the client without a secret, deliberately -- a secret in Git is a secret.
    // A DEV bootstrap reads the generated one, which is exactly what this does.
    JsonNode clients = adminGet("/admin/realms/" + REALM + "/clients?clientId=ramals-registration-admin");
    assertThat(clients.size()).as("the realm must define ramals-registration-admin").isEqualTo(1);
    String internalId = clients.get(0).get("id").asString();
    String secret = adminGet("/admin/realms/" + REALM + "/clients/" + internalId + "/client-secret")
        .get("value").asString();

    RegistrationProperties properties = new RegistrationProperties();
    properties.setEnabled(true);
    properties.getKeycloak().setBaseUrl(baseUrl);
    properties.getKeycloak().setRealm(REALM);
    properties.getKeycloak().setClientId("ramals-registration-admin");
    properties.getKeycloak().setClientSecret(secret);
    adapter = new KeycloakRegistrationAdminClient(properties);
  }

  private static RegistrationRequest request(String email, String password) {
    return new RegistrationRequest("Asha", "Iyer", email, "9876543210", "IN", "Pune",
        password, password, "terms-v1", "privacy-v1", "adult-18-v1", true, true, true);
  }

  private static String uniqueEmail() {
    return "qual-" + UUID.randomUUID() + "@example.com";
  }

  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("creates a learner identity and reports that it created it")
  void createsALearnerIdentity() throws Exception {
    String email = uniqueEmail();
    IdentityProviderPort.Identity identity =
        adapter.createLearner(UUID.randomUUID().toString(), request(email, "correct horse battery"));

    assertThat(identity.createdByThisOperation()).isTrue();
    assertThat(identity.subject()).isNotBlank();
    assertThat(identity.emailVerified()).isFalse();

    JsonNode user = adminGet("/admin/realms/" + REALM + "/users/" + identity.subject());
    assertThat(user.get("email").asString()).isEqualTo(email);
    assertThat(user.get("enabled").asBoolean()).isTrue();
    assertThat(user.get("emailVerified").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("assigns LEARNER and nothing else")
  void assignsOnlyTheLearnerRole() throws Exception {
    IdentityProviderPort.Identity identity = adapter.createLearner(
        UUID.randomUUID().toString(), request(uniqueEmail(), "correct horse battery"));

    JsonNode mappings =
        adminGet("/admin/realms/" + REALM + "/users/" + identity.subject() + "/role-mappings/realm");
    List<String> roles = mappings.valueStream().map(node -> node.get("name").asString()).toList();

    assertThat(roles).contains("LEARNER");
    assertThat(roles).doesNotContain("INSTRUCTOR", "CONTENT_AUTHOR", "ADMIN", "SERVICE");
  }

  @Test
  @DisplayName("Keycloak enforces the realm password policy on the credential we set")
  void weakPasswordsAreRefusedByTheRealm() {
    // Proves the credential is genuinely handed to Keycloak and evaluated, rather than dropped.
    // Password *login* cannot be qualified here: no client in the realm enables direct access
    // grants, which is the correct posture and is why that remains NOT VERIFIED.
    assertThatThrownBy(() -> adapter.createLearner(
        UUID.randomUUID().toString(), request(uniqueEmail(), "short")))
        .isInstanceOf(RegistrationException.class)
        .extracting(failure -> ((RegistrationException) failure).code())
        .isEqualTo("IDENTITY_PROVIDER_UNAVAILABLE");
  }

  @Test
  @DisplayName("a second registration for the same email is reported as pre-existing")
  void duplicateEmailIsReportedAsNotCreatedByThisOperation() {
    String email = uniqueEmail();
    IdentityProviderPort.Identity first =
        adapter.createLearner(UUID.randomUUID().toString(), request(email, "correct horse battery"));
    IdentityProviderPort.Identity second =
        adapter.createLearner(UUID.randomUUID().toString(), request(email, "different passphrase"));

    assertThat(first.createdByThisOperation()).isTrue();
    // The flag is what stops RegistrationService writing the second submission's name and mobile
    // against the first learner.
    assertThat(second.createdByThisOperation()).isFalse();
    assertThat(second.subject()).isEqualTo(first.subject());
  }

  @Test
  @DisplayName("a retry of the same operation is recognised as its own earlier attempt")
  void sameOperationRetryIsRecognisedAsOurs() {
    String email = uniqueEmail();
    String operationId = UUID.randomUUID().toString();
    IdentityProviderPort.Identity first = adapter.createLearner(operationId, request(email, "correct horse battery"));
    // The lost-response case: same Idempotency-Key, so the same operation id reaches Keycloak, which
    // answers 409. Reading back our own stamp distinguishes this from somebody else's account.
    IdentityProviderPort.Identity retry = adapter.createLearner(operationId, request(email, "correct horse battery"));

    assertThat(retry.subject()).isEqualTo(first.subject());
    assertThat(retry.createdByThisOperation()).isTrue();
  }

  @Test
  @DisplayName("requests a verification email and reconciles the resulting state")
  void requestsVerificationAndReconcilesTrustedState() throws Exception {
    IdentityProviderPort.Identity identity = adapter.createLearner(
        UUID.randomUUID().toString(), request(uniqueEmail(), "correct horse battery"));

    adapter.sendVerificationEmail(identity.subject());
    assertThat(adapter.emailVerified(identity.subject())).isFalse();

    // Keycloak is the authority; flipping it there is what RAMALS then reconciles from.
    adminPut("/admin/realms/" + REALM + "/users/" + identity.subject(),
        Map.of("emailVerified", true));
    assertThat(adapter.emailVerified(identity.subject())).isTrue();
  }

  // -------------------------------------------------------------------------------------------
  // Least privilege, read from Keycloak rather than from the realm file
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the registration admin holds manage-users and view-users, and nothing wider")
  void adminClientHoldsOnlyUserManagement() throws Exception {
    JsonNode account = adminGet(
        "/admin/realms/" + REALM + "/clients/" + internalId("ramals-registration-admin")
            + "/service-account-user");
    String serviceAccountId = account.get("id").asString();

    JsonNode realmManagement = adminGet("/admin/realms/" + REALM + "/clients?clientId=realm-management");
    String realmManagementId = realmManagement.get(0).get("id").asString();
    JsonNode granted = adminGet("/admin/realms/" + REALM + "/users/" + serviceAccountId
        + "/role-mappings/clients/" + realmManagementId + "/composite");
    List<String> roles = granted.valueStream().map(node -> node.get("name").asString()).toList();

    assertThat(roles).contains("manage-users", "view-users");
    assertThat(roles).doesNotContain("realm-admin", "manage-realm", "manage-clients",
        "manage-authorization", "manage-identity-providers");
  }

  @Test
  @DisplayName("the registration admin token cannot perform realm-wide administration")
  void adminClientCannotAdministerTheRealm() throws Exception {
    // The grant list above is a claim about configuration; this is the behaviour. A token minted by
    // the registration client must be refused realm configuration and client administration.
    String token = registrationAdminToken();
    assertThat(statusOf("PUT", "/admin/realms/" + REALM, token))
        .as("modifying realm configuration must be refused").isEqualTo(403);
    assertThat(statusOf("GET", "/admin/realms/" + REALM + "/clients", token))
        .as("listing clients must be refused").isEqualTo(403);
    assertThat(statusOf("POST", "/admin/realms/" + REALM + "/clients", token))
        .as("creating clients must be refused").isEqualTo(403);
    assertThat(statusOf("GET", "/admin/realms/" + REALM + "/roles", token))
        .as("enumerating realm roles must be refused").isEqualTo(403);
    // Keycloak does return a reduced realm representation to any authenticated admin-API caller, so
    // GET on the realm is not a useful negative. What matters is that nothing here can change the
    // realm, reach clients, or read the role catalogue.
    // And the operations it does need still work, so the refusals above are not a broken credential.
    assertThat(statusOf("GET", "/admin/realms/" + REALM + "/users?max=1", token)).isEqualTo(200);
  }

  // -------------------------------------------------------------------------------------------
  // Admin helpers
  // -------------------------------------------------------------------------------------------

  private static String internalId(String clientId) throws Exception {
    return adminGet("/admin/realms/" + REALM + "/clients?clientId=" + clientId).get(0).get("id").asString();
  }

  private static String bootstrapToken() throws Exception {
    String form = "grant_type=password&client_id=admin-cli&username="
        + required("RAMALS_TEST_KEYCLOAK_ADMIN") + "&password="
        + java.net.URLEncoder.encode(required("RAMALS_TEST_KEYCLOAK_ADMIN_PASSWORD"), StandardCharsets.UTF_8);
    HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/realms/master/protocol/openid-connect/token"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    return JSON.readTree(response.body()).get("access_token").asString();
  }

  private static String registrationAdminToken() throws Exception {
    String internal = internalId("ramals-registration-admin");
    String secret = adminGet("/admin/realms/" + REALM + "/clients/" + internal + "/client-secret")
        .get("value").asString();
    String form = "grant_type=client_credentials&client_id=ramals-registration-admin&client_secret="
        + java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8);
    HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + "/realms/" + REALM + "/protocol/openid-connect/token"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    return JSON.readTree(response.body()).get("access_token").asString();
  }

  private static JsonNode adminGet(String path) throws Exception {
    HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + path))
        .header("Authorization", "Bearer " + bootstrapToken)
        .GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("GET %s", path).isBetween(200, 299);
    return JSON.readTree(response.body());
  }

  private static void adminPut(String path, Map<String, Object> body) throws Exception {
    HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + path))
        .header("Authorization", "Bearer " + bootstrapToken)
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("PUT %s", path).isBetween(200, 299);
  }

  private static int statusOf(String method, String path, String token) throws Exception {
    HttpRequest.BodyPublisher body = "GET".equals(method)
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString("{}");
    return HTTP.send(HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + path))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .method(method, body).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be set for the Keycloak boundary suite.");
    }
    return value;
  }
}
