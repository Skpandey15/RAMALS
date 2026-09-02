package io.ramals.learningplatform.registration;

import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The Keycloak adapter for professional registration, running as a dedicated least-privilege
 * service account (M1-ADR-014).
 *
 * <p>Three things it is careful about. It never puts a learner's email in a recorded URI - every
 * call uses RestClient's URI template form, so the client observation records the template rather
 * than the expanded address, which would otherwise publish each registering learner's email into
 * the trace backend. It caches the access token, because a grant per HTTP call cost four round
 * trips before the first useful request. And it reports whether it created the identity, which is
 * the difference between an idempotent retry and an account-integrity defect.
 */
@Component
class KeycloakRegistrationAdminClient implements IdentityProviderPort {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(KeycloakRegistrationAdminClient.class);

  /** The only realm role public registration may ever produce (§7, M1-ADR-015). */
  private static final String LEARNER_ROLE = "LEARNER";

  /**
   * The user attribute carrying the registration operation that created the identity.
   *
   * <p>This is the non-secret stable identifier §8 requires for reconciling an ambiguous create. It
   * is written at creation and read back when a create fails in a way that leaves the outcome
   * unknown, which is what lets a retry tell "my earlier attempt succeeded and I lost the response"
   * apart from "somebody else already owns this email".
   */
  private static final String OPERATION_ATTRIBUTE = "ramals_registration_operation";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Refresh this long before the token actually expires.
   *
   * <p>Sized against the read timeout above rather than picked round: a token that outlives the
   * cache check by less than the longest request it could be used for is a token that can expire
   * mid-flight, which surfaces as an intermittent 401 that no amount of reading the code explains.
   */
  private static final Duration TOKEN_REFRESH_SKEW = Duration.ofSeconds(30);

  private final RegistrationProperties properties;
  private final RestClient http;
  private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

  KeycloakRegistrationAdminClient(RegistrationProperties properties) {
    this(properties, clientWithTimeouts());
  }

  KeycloakRegistrationAdminClient(RegistrationProperties properties, RestClient http) {
    this.properties = properties;
    this.http = http;
  }

  private static RestClient clientWithTimeouts() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
    requestFactory.setReadTimeout(READ_TIMEOUT);
    // Timeouts are not optional here. This adapter is called from a public, unauthenticated route;
    // an unbounded read against a wedged Keycloak would hold a request thread per attempt and turn
    // a provider stall into a platform outage.
    return RestClient.builder().requestFactory(requestFactory).build();
  }

  /**
   * Creates the learner identity, reconciling rather than blindly retrying when the outcome is
   * unclear.
   *
   * <p>The created/pre-existing distinction is the security control. Consider a learner who exists
   * in Keycloak from just-in-time provisioning and has no contact row yet: if a 409 were reported as
   * an ordinary success, the caller would write the <em>attacker's</em> name and mobile against the
   * <em>victim's</em> learner id, and it would insert cleanly. So the flag is returned honestly and
   * {@code RegistrationService} refuses to persist for an identity it did not create. The response
   * is identical either way, which keeps this from becoming an email-existence oracle.
   *
   * <p>The password is a parameter field and nothing else: never held, logged or returned.
   */
  @Override
  public Identity createLearner(String operationId, RegistrationRequest request) {
    String email = normalizedEmail(request);
    try {
      URI location = http.post()
          .uri(realmBase() + "/users", properties.getKeycloak().getRealm())
          .headers(headers -> headers.setBearerAuth(accessToken()))
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of(
              "username", email,
              "email", email,
              "firstName", request.firstName().trim(),
              "lastName", request.lastName().trim(),
              "enabled", true,
              "emailVerified", false,
              "attributes", Map.of(OPERATION_ATTRIBUTE, List.of(operationId)),
              "credentials", List.of(
                  Map.of("type", "password", "value", request.password(), "temporary", false))))
          .retrieve()
          .toBodilessEntity()
          .getHeaders()
          .getLocation();
      if (location == null) {
        // Keycloak returns the new user's address in Location; without it we cannot learn the
        // subject, and guessing is not an option. Reconcile instead of failing outright.
        return reconcile(operationId, email, null,
            new IllegalStateException("Identity provider omitted the created identity reference."));
      }
      String subject = location.getPath().substring(location.getPath().lastIndexOf('/') + 1);
      assignLearnerRole(subject);
      BusinessEventLogger.info(LOGGER, "registration.identity.created",
          "Identity provider created a learner identity",
          Map.of("operationId", operationId, "outcome", "SUCCESS"));
      return new Identity(subject, false, true);
    } catch (RestClientResponseException failure) {
      if (failure.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
        return reconcile(operationId, email, "CONFLICT", failure);
      }
      throw providerFailure("createLearner", operationId, failure);
    } catch (RestClientException ambiguous) {
      // Connection reset, read timeout, DNS failure: the request may or may not have been applied.
      // §8 forbids retrying the create; look for the identity instead.
      return reconcile(operationId, email, "AMBIGUOUS", ambiguous);
    }
  }

  /**
   * Resolves an unclear create by reading back our own operation stamp. Matching means an earlier
   * attempt of this operation created the user and the caller may continue; not matching means the
   * identity belongs to someone else and must not be touched.
   */
  private Identity reconcile(String operationId, String email, String reason, Exception cause) {
    ProviderUser existing;
    try {
      existing = findByEmail(email);
    } catch (RestClientException lookupFailed) {
      throw providerFailure("reconcileByEmail", operationId, lookupFailed);
    }
    if (existing == null) {
      throw providerFailure("createLearner", operationId, cause);
    }
    boolean ours = operationId.equals(existing.operationId());
    BusinessEventLogger.warn(LOGGER, "registration.identity.reconciled",
        "Identity create was reconciled against provider state",
        Map.of(
            "operationId", operationId,
            "reason", reason == null ? "NO_LOCATION" : reason,
            "createdByThisOperation", ours,
            "outcome", ours ? "SUCCESS" : "REJECTED"));
    if (ours) {
      // Our own earlier attempt got as far as creating the user. The role assignment may not have
      // run, and it is idempotent, so re-apply it rather than assume.
      assignLearnerRole(existing.subject());
    }
    return new Identity(existing.subject(), existing.emailVerified(), ours);
  }

  @Override
  public boolean emailVerified(String subject) {
    try {
      Map<String, Object> user = http.get()
          .uri(realmBase() + "/users/{subject}", properties.getKeycloak().getRealm(), subject)
          .headers(headers -> headers.setBearerAuth(accessToken()))
          .retrieve()
          .body(Map.class);
      return user != null && Boolean.TRUE.equals(user.get("emailVerified"));
    } catch (RestClientException failure) {
      throw providerFailure("emailVerified", null, failure);
    }
  }

  @Override
  public Optional<String> unverifiedSubjectForEmail(String email) {
    try {
      ProviderUser user = findByEmail(email);
      // Absent and already-verified deliberately produce the same empty result. See the port.
      if (user == null || user.emailVerified()) {
        return Optional.empty();
      }
      return Optional.of(user.subject());
    } catch (RestClientException failure) {
      throw providerFailure("unverifiedSubjectForEmail", null, failure);
    }
  }

  @Override
  public void sendVerificationEmail(String subject) {
    RegistrationProperties.Keycloak keycloak = properties.getKeycloak();
    try {
      http.put()
          .uri(realmBase() + "/users/{subject}/send-verify-email"
                  + "?client_id={verificationClientId}&redirect_uri={verificationRedirectUri}",
              keycloak.getRealm(), subject, keycloak.getVerificationClientId(),
              keycloak.getVerificationRedirectUri())
          .headers(headers -> headers.setBearerAuth(accessToken()))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException failure) {
      throw providerFailure("sendVerificationEmail", null, failure);
    }
  }

  /**
   * Looks a user up by exact email.
   *
   * <p>The email travels as a URI template variable, so the observation records the template and the
   * client performs the encoding. Nothing here writes the address to a log.
   */
  private ProviderUser findByEmail(String email) {
    // briefRepresentation=false is required: the default search omits attributes, so the operation
    // stamp would always read as absent and every reconciled retry would look like somebody else's
    // pre-existing account.
    List<Map<String, Object>> users = http.get()
        .uri(realmBase() + "/users?exact=true&briefRepresentation=false&email={email}",
            properties.getKeycloak().getRealm(), email)
        .headers(headers -> headers.setBearerAuth(accessToken()))
        .retrieve()
        .body(List.class);
    if (users == null || users.isEmpty()) {
      return null;
    }
    Map<String, Object> user = users.getFirst();
    return new ProviderUser(
        String.valueOf(user.get("id")),
        Boolean.TRUE.equals(user.get("emailVerified")),
        firstAttribute(user, OPERATION_ATTRIBUTE));
  }

  @SuppressWarnings("unchecked")
  private static String firstAttribute(Map<String, Object> user, String name) {
    Object attributes = user.get("attributes");
    if (!(attributes instanceof Map<?, ?> map)) {
      return null;
    }
    Object values = ((Map<String, Object>) map).get(name);
    if (values instanceof List<?> list && !list.isEmpty()) {
      return String.valueOf(list.getFirst());
    }
    return values == null ? null : String.valueOf(values);
  }

  /**
   * Assigns the single realm role public registration may produce.
   *
   * <p>{@link #LEARNER_ROLE} is a constant and this takes no role parameter, so request data cannot
   * influence which role is granted.
   *
   * <p>Read from the user's assignable list, not {@code GET /roles/{name}}: fetching a realm role by
   * name needs {@code view-realm}, which this account deliberately lacks, so that call returns 403
   * under the intended grant and failed the whole registration. Already-assigned is success, since a
   * reconciled retry no longer sees the role in {@code available}.
   */
  private void assignLearnerRole(String subject) {
    try {
      Map<String, Object> role = findAssignableLearnerRole(subject);
      if (role == null) {
        if (hasLearnerRole(subject)) {
          return;
        }
        throw new IllegalStateException("Realm role " + LEARNER_ROLE + " is not assignable.");
      }
      http.post()
          .uri(realmBase() + "/users/{subject}/role-mappings/realm",
              properties.getKeycloak().getRealm(), subject)
          .headers(headers -> headers.setBearerAuth(accessToken()))
          .contentType(MediaType.APPLICATION_JSON)
          .body(List.of(role))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException failure) {
      throw providerFailure("assignLearnerRole", null, failure);
    }
  }

  private Map<String, Object> findAssignableLearnerRole(String subject) {
    return firstNamed(http.get()
        .uri(realmBase() + "/users/{subject}/role-mappings/realm/available",
            properties.getKeycloak().getRealm(), subject)
        .headers(headers -> headers.setBearerAuth(accessToken()))
        .retrieve()
        .body(List.class));
  }

  private boolean hasLearnerRole(String subject) {
    return firstNamed(http.get()
        .uri(realmBase() + "/users/{subject}/role-mappings/realm",
            properties.getKeycloak().getRealm(), subject)
        .headers(headers -> headers.setBearerAuth(accessToken()))
        .retrieve()
        .body(List.class)) != null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> firstNamed(List<Map<String, Object>> roles) {
    if (roles == null) {
      return null;
    }
    for (Map<String, Object> role : roles) {
      if (LEARNER_ROLE.equals(role.get("name"))) {
        return role;
      }
    }
    return null;
  }

  /**
   * Returns a cached client-credentials token, fetching a new one only when the held token is
   * missing or close to expiry.
   */
  private String accessToken() {
    CachedToken current = cachedToken.get();
    Instant now = Instant.now();
    if (current != null && current.usableAt(now)) {
      return current.value();
    }
    CachedToken refreshed = fetchToken(now);
    cachedToken.set(refreshed);
    return refreshed.value();
  }

  private CachedToken fetchToken(Instant now) {
    RegistrationProperties.Keycloak keycloak = properties.getKeycloak();
    try {
      Map<String, Object> body = http.post()
          .uri(keycloak.getBaseUrl() + "/realms/{realm}/protocol/openid-connect/token",
              keycloak.getRealm())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form(keycloak))
          .retrieve()
          .body(Map.class);
      if (body == null || body.get("access_token") == null) {
        throw new IllegalStateException("Identity provider returned no access token.");
      }
      // expires_in is advisory; treat an absent or unparseable value as a very short life rather
      // than as forever, so a malformed response degrades into extra fetches and not into 401s.
      long expiresIn = body.get("expires_in") instanceof Number seconds ? seconds.longValue() : 60L;
      return new CachedToken(
          String.valueOf(body.get("access_token")), now.plusSeconds(expiresIn));
    } catch (RestClientException failure) {
      throw providerFailure("token", null, failure);
    }
  }

  /**
   * Builds the token form body. The secret goes in a body rather than a query string and is never
   * logged; {@link #providerFailure} records the operation and status only.
   */
  private static String form(RegistrationProperties.Keycloak keycloak) {
    return "grant_type=client_credentials"
        + "&client_id=" + encode(keycloak.getClientId())
        + "&client_secret=" + encode(keycloak.getClientSecret());
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(
        value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * Converts a provider failure into a domain failure, logging its shape and nothing else.
   *
   * <p>Deliberately excludes the provider's response body: Keycloak error bodies routinely echo the
   * submitted representation, which for the create call contains the credential.
   */
  private RegistrationException providerFailure(
      String operation, String operationId, Exception cause) {
    int status = cause instanceof RestClientResponseException response
        ? response.getStatusCode().value()
        : 0;
    BusinessEventLogger.error(LOGGER, "registration.identity.failed",
        "Identity provider call failed", cause,
        Map.of(
            "providerOperation", operation,
            "operationId", operationId == null ? "" : operationId,
            "providerStatus", status,
            "outcome", "FAILURE"));
    return RegistrationException.identityProviderUnavailable(operation, cause);
  }

  private String realmBase() {
    return properties.getKeycloak().getBaseUrl() + "/admin/realms/{realm}";
  }

  private static String normalizedEmail(RegistrationRequest request) {
    return request.email().trim().toLowerCase(java.util.Locale.ROOT);
  }

  /** A provider user, reduced to the three facts registration reasons about. */
  private record ProviderUser(String subject, boolean emailVerified, String operationId) {
  }

  private record CachedToken(String value, Instant expiresAt) {

    boolean usableAt(Instant now) {
      return now.isBefore(expiresAt.minus(TOKEN_REFRESH_SKEW));
    }
  }
}
