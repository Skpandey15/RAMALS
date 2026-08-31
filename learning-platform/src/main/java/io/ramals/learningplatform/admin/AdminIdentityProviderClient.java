package io.ramals.learningplatform.admin;

import io.ramals.learningplatform.registration.RegistrationProperties;
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
 * <p>The browser never receives the confidential client credential. The adapter reuses the
 * dedicated least-privilege service account already used for registration; the realm grants it
 * manage-users/view-users only. It therefore cannot mutate realm configuration or clients.
 */
@Component
public class AdminIdentityProviderClient {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration TOKEN_SKEW = Duration.ofSeconds(30);

  private final RegistrationProperties properties;
  private final RestClient http;
  private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

  public AdminIdentityProviderClient(RegistrationProperties properties) {
    this.properties = properties;
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
    requestFactory.setReadTimeout(READ_TIMEOUT);
    this.http = RestClient.builder().requestFactory(requestFactory).build();
  }

  public List<AdminIdentityUser> listUsers() {
    try {
      List<Map<String, Object>> users = http.get()
          .uri(realmBase() + "/users?first=0&max=200", properties.getKeycloak().getRealm())
          .headers(headers -> headers.setBearerAuth(accessToken()))
          .retrieve()
          .body(List.class);
      if (users == null) {
        return List.of();
      }
      List<AdminIdentityUser> result = new ArrayList<>();
      for (Map<String, Object> user : users) {
        String id = String.valueOf(user.get("id"));
        result.add(new AdminIdentityUser(
            id,
            string(user.get("username")),
            string(user.get("email")),
            Boolean.TRUE.equals(user.get("enabled")),
            effectiveRealmRoles(id)));
      }
      return List.copyOf(result);
    } catch (RestClientException failure) {
      throw new AdminIdentityProviderException("listUsers", failure);
    }
  }

  public Set<String> effectiveRealmRoles(String userId) {
    try {
      List<Map<String, Object>> roles = http.get()
          .uri(realmBase() + "/users/{userId}/role-mappings/realm/composite",
              properties.getKeycloak().getRealm(), userId)
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
    try {
      http.put()
          .uri(realmBase() + "/users/{userId}", properties.getKeycloak().getRealm(), userId)
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
    Map<String, Object> role = findAvailableRole(userId, roleName);
    if (role == null) {
      if (effectiveRealmRoles(userId).contains(roleName)) {
        return;
      }
      throw new IllegalArgumentException("Requested realm role is not assignable.");
    }
    try {
      http.post()
          .uri(realmBase() + "/users/{userId}/role-mappings/realm",
              properties.getKeycloak().getRealm(), userId)
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
    Map<String, Object> assigned = findAssignedRole(userId, roleName);
    if (assigned == null) {
      return;
    }
    try {
      http.method(HttpMethod.DELETE)
          .uri(realmBase() + "/users/{userId}/role-mappings/realm",
              properties.getKeycloak().getRealm(), userId)
          .headers(headers -> headers.setBearerAuth(accessToken()))
          .contentType(MediaType.APPLICATION_JSON)
          .body(List.of(assigned))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException failure) {
      throw new AdminIdentityProviderException("removeRealmRole", failure);
    }
  }

  private Map<String, Object> findAvailableRole(String userId, String roleName) {
    try {
      List<Map<String, Object>> roles = http.get()
          .uri(realmBase() + "/users/{userId}/role-mappings/realm/available",
              properties.getKeycloak().getRealm(), userId)
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
          .uri(realmBase() + "/users/{userId}/role-mappings/realm",
              properties.getKeycloak().getRealm(), userId)
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
      form.add("client_id", properties.getKeycloak().getClientId());
      form.add("client_secret", properties.getKeycloak().getClientSecret());
      Map<String, Object> response = http.post()
          .uri(properties.getKeycloak().getBaseUrl()
              + "/realms/{realm}/protocol/openid-connect/token", properties.getKeycloak().getRealm())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .body(Map.class);
      if (response == null || response.get("access_token") == null) {
        throw new IllegalStateException("Identity provider omitted access_token.");
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

  private String realmBase() {
    return properties.getKeycloak().getBaseUrl() + "/admin/realms/{realm}";
  }

  private static String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private record CachedToken(String value, Instant expiresAt) {}
}
