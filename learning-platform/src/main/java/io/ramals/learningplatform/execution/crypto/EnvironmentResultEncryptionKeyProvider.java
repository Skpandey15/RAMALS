package io.ramals.learningplatform.execution.crypto;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;

/**
 * The initial adapter: key material from the environment-backed secret channel.
 *
 * <p>M2-ADR-018 §8 chose this over a vendor KMS because the platform has not committed to one, and
 * introducing a cloud dependency ahead of that decision would be a commitment nobody asked for. It
 * is one of two implementations the port is expected to have; the other does not exist yet, and the
 * point of the port is that adding it touches nothing but this package.
 *
 * <p>Every failure path throws {@link ResultEncryptionKeyUnavailableException}. There is no branch
 * that returns null, substitutes the active key for a missing one, or reports success with unusable
 * material -- M2-ADR-018 §10 requires refusing rather than degrading, and a provider that quietly
 * resolved a stale id to the current key would silently decrypt one key's ciphertext with another
 * and fail at the cipher instead, where the cause is far harder to see.
 */
public class EnvironmentResultEncryptionKeyProvider implements ResultEncryptionKeyProvider {

  /** AES-256, per the envelope format in M2-ADR-018 §7. */
  private static final int REQUIRED_KEY_BYTES = 32;
  private static final String ALGORITHM = "AES";

  private final ResultEncryptionKeyProperties properties;

  public EnvironmentResultEncryptionKeyProvider(ResultEncryptionKeyProperties properties) {
    this.properties = properties;
  }

  @Override
  public String activeKeyId() {
    String active = properties.getActiveKeyId();
    if (active == null || active.isBlank()) {
      throw new ResultEncryptionKeyUnavailableException(null, "no active key id is configured");
    }
    // Checked here as well as in keyFor, so a misconfiguration surfaces when the active key is
    // asked for rather than at the first write that needs it.
    if (!properties.getKeys().containsKey(active)) {
      throw new ResultEncryptionKeyUnavailableException(
          active, "the active key id has no configured material");
    }
    return active;
  }

  @Override
  public ResultEncryptionKey keyFor(String keyId) {
    if (keyId == null || keyId.isBlank()) {
      throw new ResultEncryptionKeyUnavailableException(keyId, "a key id is required");
    }
    String encoded = properties.getKeys().get(keyId);
    if (encoded == null || encoded.isBlank()) {
      throw new ResultEncryptionKeyUnavailableException(keyId, "no material is configured for it");
    }

    byte[] material;
    try {
      material = Base64.getDecoder().decode(encoded.trim());
    } catch (IllegalArgumentException malformed) {
      // The decoder's message can echo the offending input, which here is key material. Neither the
      // message nor the cause is propagated.
      throw new ResultEncryptionKeyUnavailableException(keyId, "material is not valid base64");
    }
    if (material.length != REQUIRED_KEY_BYTES) {
      // The length is reported, the bytes are not. A short key is a configuration error worth
      // naming precisely, and its size gives an operator the fix without revealing the value.
      throw new ResultEncryptionKeyUnavailableException(
          keyId, "material must be " + REQUIRED_KEY_BYTES + " bytes, was " + material.length);
    }
    return new ResultEncryptionKey(keyId, new SecretKeySpec(material, ALGORITHM));
  }
}
