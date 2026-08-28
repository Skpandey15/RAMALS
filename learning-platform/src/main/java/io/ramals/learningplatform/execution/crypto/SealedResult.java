package io.ramals.learningplatform.execution.crypto;

/**
 * A sealed Contract B result: the envelope bytes, and the key id that produced them.
 *
 * <p>The key id is returned alongside the envelope rather than left for the caller to re-derive,
 * because M2-ADR-018 §7 stores it twice — inside the envelope, and again as the
 * {@code encryption_key_id} column. <em>"Duplicated on purpose, because the column is what a
 * rotation sweep queries on and the envelope is what makes a value self-contained."</em> Handing
 * back both is what stops a caller writing one key's id against another key's ciphertext.
 *
 * @param keyId the key the result was sealed under, for the column
 * @param envelope the self-describing envelope, for the {@code normalized_result} BYTEA
 */
public record SealedResult(String keyId, byte[] envelope) {

  /**
   * Envelope length only, never its content.
   *
   * <p>The record's generated {@code toString} would print the ciphertext array reference, which is
   * harmless, but a future change to a byte-rendering form would not be. Overridden so the safe
   * behaviour is stated rather than inherited by luck.
   */
  @Override
  public String toString() {
    return "SealedResult[keyId=" + keyId + ", envelopeBytes=" + envelope.length + "]";
  }
}
