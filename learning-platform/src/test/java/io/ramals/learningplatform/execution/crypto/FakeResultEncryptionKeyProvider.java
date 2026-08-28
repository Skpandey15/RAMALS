package io.ramals.learningplatform.execution.crypto;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic in-memory provider, for tests only.
 *
 * <p>Test-source only, deliberately. M2-ADR-018 §8's port exists so a key source can be substituted;
 * a fake that lived in main sources would be a second production implementation nobody meant to
 * ship, and the first thing reached for when configuration is inconvenient.
 *
 * <p>Material is derived from the key id, so two versions are distinguishable and every run
 * produces the same bytes. It is obviously synthetic and is not a secret: nothing here is a key any
 * deployment would ever hold.
 */
public final class FakeResultEncryptionKeyProvider implements ResultEncryptionKeyProvider {

  private final Map<String, byte[]> keys = new LinkedHashMap<>();
  private String activeKeyId;

  public FakeResultEncryptionKeyProvider with(String keyId) {
    byte[] material = new byte[32];
    // Derived from the id so a test can assert two versions differ without hardcoding bytes.
    for (int i = 0; i < material.length; i++) {
      material[i] = (byte) (keyId.hashCode() + i);
    }
    keys.put(keyId, material);
    if (activeKeyId == null) {
      activeKeyId = keyId;
    }
    return this;
  }

  public FakeResultEncryptionKeyProvider active(String keyId) {
    this.activeKeyId = keyId;
    return this;
  }

  @Override
  public String activeKeyId() {
    if (activeKeyId == null) {
      throw new ResultEncryptionKeyUnavailableException(null, "no active key id is configured");
    }
    return activeKeyId;
  }

  @Override
  public ResultEncryptionKey keyFor(String keyId) {
    byte[] material = keyId == null ? null : keys.get(keyId);
    if (material == null) {
      // Fails closed exactly as the real adapter does. A fake that returned a default key here
      // would let a test pass against behaviour production does not have.
      throw new ResultEncryptionKeyUnavailableException(keyId, "unknown key id");
    }
    return new ResultEncryptionKey(keyId, new SecretKeySpec(material, "AES"));
  }
}
