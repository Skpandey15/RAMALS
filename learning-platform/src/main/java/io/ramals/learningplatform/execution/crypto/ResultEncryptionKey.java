package io.ramals.learningplatform.execution.crypto;

import java.util.Objects;
import javax.crypto.SecretKey;

/**
 * One key, carrying the identity the envelope records alongside the material that opens it.
 *
 * <p>Identity travels with material because M2-ADR-018 §7 stores {@code encryption_key_id} inside
 * the envelope and again as a column, and a caller that had material without knowing which id
 * produced it could not write either. Pairing them here removes the chance of recording one key's
 * id against another key's ciphertext.
 *
 * <p>Holds a {@link SecretKey} rather than a {@code byte[]}. The JCE type is what a cipher wants,
 * it can be destroyed, and it does not invite the array copies that leave key bytes scattered
 * across a heap. There is deliberately no accessor returning raw bytes.
 */
public final class ResultEncryptionKey {

  private final String keyId;
  private final SecretKey material;

  public ResultEncryptionKey(String keyId, SecretKey material) {
    if (keyId == null || keyId.isBlank()) {
      throw new IllegalArgumentException("a result encryption key must carry its id");
    }
    this.keyId = keyId;
    this.material = Objects.requireNonNull(material, "key material");
  }

  /** The id recorded in the envelope and the {@code encryption_key_id} column. */
  public String keyId() {
    return keyId;
  }

  /** The material, for a cipher. Callers must not copy it out or hold it beyond one operation. */
  public SecretKey material() {
    return material;
  }

  /**
   * Identity only, never material.
   *
   * <p>Overridden rather than inherited because the default for a key-bearing object is a hazard:
   * one interpolation into a log line or an exception message is all it takes, and M2-ADR-018 §10
   * requires that plaintext and key material never reach logs on any path, including failures.
   */
  @Override
  public String toString() {
    return "ResultEncryptionKey[keyId=" + keyId + ", material=REDACTED]";
  }

  /**
   * Identity only.
   *
   * <p>Comparing material would make two distinct key versions that happen to share bytes equal,
   * which is exactly the confusion rotation must not permit -- and would put key comparison on a
   * timing-sensitive path for no benefit.
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof ResultEncryptionKey key && keyId.equals(key.keyId);
  }

  @Override
  public int hashCode() {
    return keyId.hashCode();
  }
}
