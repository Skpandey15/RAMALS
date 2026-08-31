package io.ramals.learningplatform.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Server-side Keycloak administration adapter for human administrative operations.
 *
 * <p>The browser never receives the confidential client credential. M1-ADR-017 gives this surface
 * its own {@code ramals-identity-admin} workload identity rather than reusing the registration
 * credential. The effective realm permission ceiling remains manage-users/view-users; realm and
 * client administration are outside this adapter's trust domain.
 */
@Component
public class AdminIdentityProviderClient {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration TOKEN_SKEW = Duration.ofSeconds(30);
  private static final int USER_PAGE_SIZE = 200;

  private final AdminIdentityProperties properties;
  private final RestClient http;
  private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

  /** The sole constructor Spring may select for the component. */
  public AdminIdentityProviderClient(AdminIdentityProperties properties) {
    this(properties, newRestClient());
  }

  private AdminIdentityProviderClient(AdminIdentityProperties properties, RestClient http) {
    this.properties = properties;
    this.http = http;
  }

  /** Package-scoped test seam without exposing a second constructor to Spring bean discovery. */
  static AdminIdentityProviderClient forTesting(AdminIdentityProperties properties, RestClient http) {
    return new AdminIdentityProviderClient(properties, http);
  }

  public List<AdminIdentityUser> listUsers() {
    requireConfigured();
    try {
      List<AdminIdentityUser> result = new ArrayList<>();
      int first = 0;
      while (true) {
        List<Map<String, Object>> users = http.get()
            .uri(realmBase() + "/users?first={first}&max={max}",
                properties.getRealm(), first, USER_PAGE_SIZE)
            .headers(headers -> headers.setBearerAuth(accessToken()))
            .retrieve()
            .body(List.class);
        if (users == null || users.isEmpty()) {
          break;
        }
        for (Map<String, Object> user : users) {
          result.add(toAdminIdentityUser(user));
        }
        if (users.size() < USER_PAGE_SIZE) {
          break;
        }
        first += users.size();
      }
      return List.copyOf(result);
    } catch (RestClientException failure) {
      throw new AdminIdentityProviderException("listUsers", failure);
    }
  }

  /** Fetches the post-mutation target directly, independent of list pagination. */
  public AdminIdentityUser getUser(String userId) {
    requireConfigured();
    try {
      return toAdminIdentityUser(fetchUserRepresentation(userId));
    } catch (RestClientException failure) {
      throw new AdminIdentityProviderException("getUser", failure);
    }
  }

  /**
   * Keycloak service accounts are workload identities even when they do not carry the RAMALS
   * SERVICE realm role. Protect them using Keycloak's service-account metadata, with the canonical
   * username prefix retained as defense in depth for older Keycloak representations.
   */
  public boolean isServiceAccount(String userId) {
    requireConfigured();
    try {
      Map<String, Object> user = fetchUserRepresentation(userId);
      Object serviceAccountClientId = user.get("serviceAccountClientId");
      String username = string(user.get("username"));
      return serviceAccountClientId != null
          || (username != null && username.startsWith("service-account-"));
    } catch (RestClientException failure) {
      throw new AdminIdentityProviderException("isServiceAccount", failure);
    }
  }

  public Set<String> effectiveRealmRoles(String userId) {
    requireConfigured();
    try {
      List<Map<String, Object>> roles = http.get()
          .uri(realmBase() + "/users/{userId}/role-mappings/realm/composite",
              properties.getRealm(), userId)
          .headers(headers -> headers.setBearerAuth(accessToken()))
          .retrieve()
          .body(List.class);
      if (roles == null) {
        return Set.of();
      }
      LinkedHashSet<String> names = new LinkedHashSet<>();
      for (Map<String, Object> role : roles) {
        Object name = role.get("name");
        if (name != null) {
          names.add(String.valueOf(name));
        }
      }
      return Set.copyOf(names);
    } catch (RestClientException failure) {
      throw new AdminIdentityProviderException("effectiveRealmRoles", failure);
    }
  }

  public void setEnabled(String userId, boolean enabled) {
    requireConfigured();
    try {
      http.put()
          .uri(realmBase() + "/users/{userId}", properties.getRealm(), userId)
          .headers(headers -> headers.setBearerAuth(accessToken()))
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("enabled", enabled))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException failure) {
      throw new AdminIdentityProviderException("setEnabled", failure);
    }
  }

  public void addRealmRole(String userId, String roleName) {
    requireConfigured();
    Map<String, Object> role = findAvailableRole(userId, roleName);
    if (role == null) {
      if (effectiveRealmRoles(userId).contains(roleName)) {
        return;
      }
      throw new IllegalArgumentException("Requested realm role is not assignable.");
    }
    try {
      http.post()
          .uri(realmBase() + "/users/{userId}/role-mappings/realm", properties.getRealm(), userId)
          .headers(headers -> headers.setBearerAuth(accessToken()))
          .contentType(MediaType.APPLICATION_JSON)
          .body(List.of(role))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException failure) {
      throw new AdminIdentityProviderException("addRealmRole", failure);
    }
  }

  public void removeRealmRole(String userId, String roleName) {
    requireConfigured();
    Map<String, Object> assigned = findAssignedRole(userId, roleName);
    if (assigned == null) {
      return;
    }
    try {
      http.method(HttpMethod.DELETE)
          .uri(realmBase() + "/users/{userId}/role-mappings/realm", properties.getRealm(), userId)
          .headers(headers -> headers.setBearerAuth(accessToken()))
          .contentType(MediaType.APPLICATION_JSON)
          .body(List.of(assigned))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException failure) {
      throw new AdminIdentityProviderException("removeRealmRole", failure);
    }
  }

  private AdminIdentityUser toAdminIdentityUser(Map<String, Object> user) {
    String id = String.valueOf(user.get("id"));
    return new AdminIdentityUser(
        id,
        string(user.get("username")),
        string(user.get("email")),
        Boolean.TRUE.equals(user.get("enabled")),
        effectiveRealmRoles(id));
  }

  private Map<String, Object> fetchUserRepresentation(String userId) {
    Map<String, Object> user = http.get()
        .uri(realmBase() + "/users/{userId}", properties.getRealm(), userId)
        .headers(headers -> headers.setBearerAuth(accessToken()))
        .retrieve()
        .body(Map.class);
    if (user == null || user.get("id") == null) {
      throw new AdminIdentityProviderException(
          "getUser", new IllegalStateException("Identity provider omitted user representation."));
    }
    return user;
  }

  private Map<String, Object> findAvailableRole(String userId, String roleName) {
    try {
      List<Map<String, Object>> roles = http.get()
          .uri(realmBase() + "/users/{userId}/role-mappings/realm/available", properties.getRealm(), userId)
          .headers(headers -> headers.setBearerAuth(accessToken()))
          .retrieve()
          .body(List.class);
      return findRole(roles, roleName);
    } catch (RestClientException failure) {
      throw new AdminIdentityProviderException("findAvailableRole", failure);
    }
  }

  private Map<String, Object> findAssignedRole(String userId, String roleName) {
    try {
      List<Map<String, Object>> roles = http.get()
          .uri(realmBase() + "/users/{userId}/role-mappings/realm", properties.getRealm(), userId)
          .headers(headers -> headers.setBearerAuth(accessToken()))
          .retrieve()
          .body(List.class);
      return findRole(roles, roleName);
    } catch (RestClientException failure) {
      throw new AdminIdentityProviderException("findAssignedRole", failure);
    }
  }

  private static Map<String, Object> findRole(List<Map<String, Object>> roles, String roleName) {
    if (roles == null) {
      return null;
    }
    for (Map<String, Object> role : roles) {
      if (roleName.equals(String.valueOf(role.get("name")))) {
        return role;
      }
    }
    return null;
  }

  private String accessToken() {
    CachedToken current = cachedToken.get();
    Instant now = Instant.now();
    if (current != null && current.expiresAt().isAfter(now.plus(TOKEN_SKEW))) {
      return current.value();
    }
    try {
      MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
      form.add("grant_type", "client_credentials");
      form.add("client_id", properties.getClientId());
      form.add("client_secret", properties.getClientSecret());
      Map<String, Object> response = http.post()
          .uri(properties.getBaseUrl() + "/realms/{realm}/protocol/openid-connect/token",
              properties.getRealm())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .body(Map.class);
      if (response == null || response.get("access_token") == null) {
        throw new AdminIdentityProviderException(
            "accessToken", new IllegalStateException("Identity provider omitted access_token."));
      }
      long expiresIn = response.get("expires_in") instanceof Number number ? number.longValue() : 60L;
      CachedToken refreshed = new CachedToken(
          String.valueOf(response.get("access_token")), now.plusSeconds(Math.max(1, expiresIn)));
      cachedToken.set(refreshed);
      return refreshed.value();
    } catch (RestClientException failure) {
      throw new AdminIdentityProviderException("accessToken", failure);
    }
  }

  private void requireConfigured() {
    if (properties.getClientSecret() == null || properties.getClientSecret().isBlank()) {
      throw new AdminIdentityProviderException(
          "configuration",
          new IllegalStateException("RAMALS_ADMIN_IDENTITY_CLIENT_SECRET is not configured."));
    }
  }

  private String realmBase() {
    return properties.getBaseUrl() + "/admin/realms/{realm}";
  }

  private static RestClient newRestClient() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
    requestFactory.setReadTimeout(READ_TIMEOUT);
    return RestClient.builder().requestFactory(requestFactory).build();
  }

  private static String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private record CachedToken(String value, Instant expiresAt) {}
}
