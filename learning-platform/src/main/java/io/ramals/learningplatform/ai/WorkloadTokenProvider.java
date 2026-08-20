package io.ramals.learningplatform.ai;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.client.RestClient;

/** Small client-credentials token cache for Spring's authenticated calls to ramals-ai. */
public final class WorkloadTokenProvider implements WorkloadToken {

  private final RestClient tokenClient;
  private final String clientId;
  private final String clientSecret;
  private final String audience;
  private String token;
  private Instant expiresAt = Instant.MIN;

  public WorkloadTokenProvider(
      RestClient tokenClient, String clientId, String clientSecret, String audience) {
    this.tokenClient = tokenClient;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.audience = audience;
  }

  @Override
  public synchronized String accessToken() {
    if (token != null && Instant.now().isBefore(expiresAt)) {
      return token;
    }
    Map<?, ?> response = tokenClient.post()
        .header("Content-Type", "application/x-www-form-urlencoded")
        .body("grant_type=client_credentials&client_id=" + encode(clientId)
            + "&client_secret=" + encode(clientSecret) + "&audience=" + encode(audience))
        .retrieve()
        .body(Map.class);
    if (response == null || !(response.get("access_token") instanceof String issued)
        || issued.isBlank()) {
      throw new AiUnavailableException("AI_WORKLOAD_TOKEN_FAILURE",
          "The AI workload token endpoint returned no access token.");
    }
    long expiresIn = response.get("expires_in") instanceof Number number
        ? number.longValue() : 60L;
    token = issued;
    expiresAt = Instant.now().plusSeconds(Math.max(1L, expiresIn - 10L));
    return token;
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }
}
