package io.ramals.learningplatform.mcp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.mcp.McpProperties;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M2-ADR-031: {@link DelegatedLearnerContextSigningKeys} resolves {@code
 * ramals.mcp.delegated-context} key material the same way {@code
 * EnvironmentResultEncryptionKeyProvider} resolves Contract B's -- validated lazily, on first call,
 * never at construction, and never with a generated or default production key.
 */
class DelegatedLearnerContextSigningKeysTests {

  // Test-only material; never a value this repository would accept as a real key.
  private static final String VALID_KEY_BASE64 =
      Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

  @Test
  void unconfiguredDelegatedContextThrowsOnFirstUseNotConstruction() {
    McpProperties.DelegatedContext properties = new McpProperties.DelegatedContext();

    DelegatedLearnerContextSigningKeys signingKeys = new DelegatedLearnerContextSigningKeys(properties);

    assertThatThrownBy(signingKeys::activeKeyId)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active-key-id is not configured");
    assertThatThrownBy(signingKeys::activeSigningKey).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void activeKeyIdNamingAKeyWithNoMaterialThrows() {
    McpProperties.DelegatedContext properties = new McpProperties.DelegatedContext();
    properties.setActiveKeyId("current");
    // keys map left empty: "current" is named but has no configured material.

    assertThatThrownBy(new DelegatedLearnerContextSigningKeys(properties)::activeKeyId)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no configured material");
  }

  @Test
  void blankKeyIdIsRejected() {
    McpProperties.DelegatedContext properties = new McpProperties.DelegatedContext();
    DelegatedLearnerContextSigningKeys signingKeys = new DelegatedLearnerContextSigningKeys(properties);

    assertThatThrownBy(() -> signingKeys.signingKeyFor(" "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("key id is required");
    assertThatThrownBy(() -> signingKeys.signingKeyFor(null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void unknownKeyIdIsRejected() {
    McpProperties.DelegatedContext properties = new McpProperties.DelegatedContext();
    properties.setKeys(Map.of("current", VALID_KEY_BASE64));

    assertThatThrownBy(() -> new DelegatedLearnerContextSigningKeys(properties).signingKeyFor("missing"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no delegated-context signing key material is configured for key id 'missing'");
  }

  @Test
  void malformedBase64IsRejectedWithoutEchoingTheValue() {
    McpProperties.DelegatedContext properties = new McpProperties.DelegatedContext();
    properties.setKeys(Map.of("current", "not-valid-base64!!"));

    assertThatThrownBy(() -> new DelegatedLearnerContextSigningKeys(properties).signingKeyFor("current"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not valid base64")
        .hasMessageNotContaining("not-valid-base64!!");
  }

  @Test
  void tooShortMaterialIsRejectedWithoutEchoingTheValue() {
    String shortKeyBase64 = Base64.getEncoder().encodeToString("too-short".getBytes());
    McpProperties.DelegatedContext properties = new McpProperties.DelegatedContext();
    properties.setKeys(Map.of("current", shortKeyBase64));

    assertThatThrownBy(() -> new DelegatedLearnerContextSigningKeys(properties).signingKeyFor("current"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be at least 32 bytes, was 9")
        .hasMessageNotContaining(shortKeyBase64);
  }

  @Test
  void validlyConfiguredActiveKeyResolvesCorrectly() {
    McpProperties.DelegatedContext properties = new McpProperties.DelegatedContext();
    properties.setActiveKeyId("current");
    properties.setKeys(Map.of("current", VALID_KEY_BASE64));

    DelegatedLearnerContextSigningKeys signingKeys = new DelegatedLearnerContextSigningKeys(properties);

    assertThat(signingKeys.activeKeyId()).isEqualTo("current");
    assertThat(signingKeys.activeSigningKey()).isEqualTo(Base64.getDecoder().decode(VALID_KEY_BASE64));
  }

  @Test
  void allSigningKeysResolvesEveryConfiguredIdIncludingARetiredOne() {
    String previousKeyBase64 = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());
    McpProperties.DelegatedContext properties = new McpProperties.DelegatedContext();
    properties.setActiveKeyId("current");
    properties.setKeys(Map.of("current", VALID_KEY_BASE64, "previous", previousKeyBase64));

    Map<String, byte[]> resolved = new DelegatedLearnerContextSigningKeys(properties).allSigningKeys();

    assertThat(resolved).containsOnlyKeys("current", "previous");
    assertThat(resolved.get("current")).isEqualTo(Base64.getDecoder().decode(VALID_KEY_BASE64));
    assertThat(resolved.get("previous")).isEqualTo(Base64.getDecoder().decode(previousKeyBase64));
  }
}
