package io.ramals.learningplatform.execution.crypto;

/**
 * Key material could not be obtained. The fail-closed signal of M2-ADR-018 §10.
 *
 * <p>Unchecked, and that is the point: the correct handling is to abandon the operation, not to
 * catch and continue. A Contract B result that cannot be encrypted is not stored, and one that
 * cannot be decrypted is not adopted -- and in particular is never treated as absent, which would
 * look like a clean re-runnable request and could resubmit to the provider.
 *
 * <p>Carries a key id and a reason, never key material and never a fragment of one. The constructor
 * is the only place a message is built, so there is one place to check that this stays true.
 */
public class ResultEncryptionKeyUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String keyId;

  public ResultEncryptionKeyUnavailableException(String keyId, String reason) {
    super("result encryption key unavailable [keyId=" + (keyId == null ? "<none>" : keyId)
        + "]: " + reason);
    this.keyId = keyId;
  }

  /** The key that could not be resolved, or null when no active key was configured at all. */
  public String keyId() {
    return keyId;
  }
}
