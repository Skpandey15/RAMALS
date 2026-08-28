package io.ramals.learningplatform.execution.crypto;

/**
 * A sealed result could not be opened: authentication failed, or the envelope is unreadable.
 *
 * <p>M2-ADR-018 §10 treats this as corruption rather than absence: <em>"Refuse, alert, do not adopt,
 * do not delete — the row is evidence."</em> Distinct from
 * {@link ResultEncryptionKeyUnavailableException} on purpose. A missing key is an operational
 * problem that may resolve when the key returns; a failed tag is a statement about the bytes, and
 * the two deserve different operator responses.
 *
 * <p>Carries a reason and, where known, the key id the envelope named. It never carries plaintext, a
 * fragment of it, key material, or the offending bytes — the message is built here and nowhere else,
 * so there is one place to check that stays true.
 */
public class ResultEnvelopeCorruptException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String keyId;

  public ResultEnvelopeCorruptException(String keyId, String reason) {
    super("contract B result envelope is unusable [keyId=" + (keyId == null ? "<unknown>" : keyId)
        + "]: " + reason);
    this.keyId = keyId;
  }

  /** The key the envelope named, or null when the envelope was too damaged to say. */
  public String keyId() {
    return keyId;
  }
}
