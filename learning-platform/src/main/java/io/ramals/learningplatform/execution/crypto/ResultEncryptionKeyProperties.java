package io.ramals.learningplatform.execution.crypto;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Contract B key configuration, supplied through the existing environment-backed secret channel.
 *
 * <p>M2-ADR-018 §8 chose that channel because it is the only mechanism this platform has, and
 * inventing a second one for this table would be worse than reusing the one every other credential
 * already uses. It is bound here rather than read from {@code System.getenv} directly so the
 * adapter stays testable without mutating process environment.
 *
 * <p><strong>No key ships in the repository.</strong> Every field defaults to empty, so a build
 * with nothing configured has no keys and fails closed rather than starting with a default one.
 */
@Validated
@ConfigurationProperties(prefix = "ramals.contract-b.encryption")
public class ResultEncryptionKeyProperties {

  /** The key id new results are encrypted under. Empty until Contract B is configured. */
  private String activeKeyId = "";

  /**
   * Key id to base64 material, holding retired keys alongside the active one.
   *
   * <p>A map rather than a single value because rotation does not re-encrypt (M2-ADR-018 §8), so
   * older ciphertext stays readable only while its key is still present.
   */
  private Map<String, String> keys = new LinkedHashMap<>();

  public String getActiveKeyId() {
    return activeKeyId;
  }

  public void setActiveKeyId(String activeKeyId) {
    this.activeKeyId = activeKeyId == null ? "" : activeKeyId.trim();
  }

  public Map<String, String> getKeys() {
    return keys;
  }

  public void setKeys(Map<String, String> keys) {
    this.keys = keys == null ? new LinkedHashMap<>() : keys;
  }

  /**
   * Ids and a count, never material.
   *
   * <p>Configuration objects reach logs and diagnostic endpoints by default, which for this one
   * would publish every key the platform holds. Overridden for the same reason
   * {@link ResultEncryptionKey#toString()} is.
   */
  @Override
  public String toString() {
    return "ResultEncryptionKeyProperties[activeKeyId=" + activeKeyId
        + ", knownKeyIds=" + keys.keySet() + ", material=REDACTED]";
  }
}
