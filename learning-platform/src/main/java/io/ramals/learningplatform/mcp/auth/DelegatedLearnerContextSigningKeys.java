package io.ramals.learningplatform.mcp.auth;

import io.ramals.learningplatform.mcp.McpProperties;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves {@link McpProperties.DelegatedContext}'s configured key material, the same way {@code
 * EnvironmentResultEncryptionKeyProvider} resolves Contract B's: base64 in, validated bytes out,
 * every failure path a plain {@link IllegalStateException} naming the problem precisely without ever
 * echoing the offending value.
 *
 * <p><strong>No production key is ever generated or defaulted here.</strong> With nothing configured
 * ({@code ramals.mcp.delegated-context.active-key-id} and {@code ...keys} both empty, MCP-1's own
 * safe default), every method on this class throws -- exactly like the encryption provider, this
 * bean is registered unconditionally-within-MCP and is harmless while unused: MCP-1 wires no {@link
 * DelegatedLearnerContextIssuer} or {@link DelegatedLearnerContextValidator} against it yet, so
 * nothing calls it, so an operator who leaves MCP disabled -- or who enables it before configuring a
 * key -- sees no startup failure, only a clear one the first time a future capability actually asks
 * for a signing key.
 */
public class DelegatedLearnerContextSigningKeys {

  /** 256-bit minimum for HS256, matching this platform's own AES-256 key-size convention. */
  private static final int MINIMUM_KEY_BYTES = 32;

  private final McpProperties.DelegatedContext properties;

  public DelegatedLearnerContextSigningKeys(McpProperties.DelegatedContext properties) {
    this.properties = properties;
  }

  /**
   * The key id new delegated-context tokens must be signed under.
   *
   * @throws IllegalStateException when no active key id is configured, or it names a key with no
   *     material
   */
  public String activeKeyId() {
    String active = properties.getActiveKeyId();
    if (active == null || active.isBlank()) {
      throw new IllegalStateException(
          "ramals.mcp.delegated-context.active-key-id is not configured");
    }
    if (!properties.getKeys().containsKey(active)) {
      throw new IllegalStateException(
          "ramals.mcp.delegated-context.active-key-id names a key with no configured material");
    }
    return active;
  }

  /**
   * Decoded signing key material for the currently active key id.
   *
   * @throws IllegalStateException per {@link #activeKeyId()} and {@link #signingKeyFor(String)}
   */
  public byte[] activeSigningKey() {
    return signingKeyFor(activeKeyId());
  }

  /**
   * Decoded, length-validated signing key material for one key id, active or retired -- retired ids
   * stay resolvable during a rotation window so already-issued tokens keep validating until expiry.
   *
   * @throws IllegalStateException when the id is blank, unknown, not valid base64, or shorter than
   *     the minimum key size. The message never echoes the configured material.
   */
  public byte[] signingKeyFor(String keyId) {
    if (keyId == null || keyId.isBlank()) {
      throw new IllegalStateException("a delegated-context key id is required");
    }
    String encoded = properties.getKeys().get(keyId);
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalStateException(
          "no delegated-context signing key material is configured for key id '" + keyId + "'");
    }

    byte[] material;
    try {
      material = Base64.getDecoder().decode(encoded.trim());
    } catch (IllegalArgumentException malformed) {
      // The decoder's own message can echo the offending input, which here is key material.
      // Neither that message nor its cause is propagated.
      throw new IllegalStateException(
          "delegated-context signing key material for '" + keyId + "' is not valid base64");
    }
    if (material.length < MINIMUM_KEY_BYTES) {
      // The length is reported, the bytes are not: a short key is a configuration error worth
      // naming precisely, and its size gives an operator the fix without revealing the value.
      throw new IllegalStateException(
          "delegated-context signing key material for '" + keyId + "' must be at least "
              + MINIMUM_KEY_BYTES + " bytes, was " + material.length);
    }
    return material;
  }

  /**
   * Every configured key id, decoded -- the exact shape {@link DelegatedLearnerContextValidator}'s
   * constructor takes, so a future integration can build one directly from this method's result
   * without re-deriving the key set by hand.
   *
   * @throws IllegalStateException if any configured key's own material fails to resolve
   */
  public Map<String, byte[]> allSigningKeys() {
    Map<String, byte[]> resolved = new LinkedHashMap<>();
    for (String keyId : properties.getKeys().keySet()) {
      resolved.put(keyId, signingKeyFor(keyId));
    }
    return resolved;
  }
}
