package io.ramals.learningplatform.execution.contractb;

/**
 * A normalized result was refused before it could be encrypted or stored.
 *
 * <p>M2-ADR-018 §10: <em>"Schema validation fails before encryption → Refuse to store. The
 * prohibition on reasoning content is enforced here, and a failure means the invariant would have
 * been broken."</em> This exception is that refusal. Reaching it means nothing was written — not a
 * row, not a partial row, and not a plaintext copy kept while someone decides what to do.
 *
 * <p>Carries a reason code and a fixed message. Never the payload, a fragment of it, or its length:
 * the rejected document is the learner's diagnosis, and a rejection is exactly the path most likely
 * to be logged verbosely by a caller trying to work out what went wrong.
 */
public class ContractBResultRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String reasonCode;

  public ContractBResultRejectedException(String reasonCode, String reason) {
    super("contract B result refused [reason=" + reasonCode + "]: " + reason);
    this.reasonCode = reasonCode;
  }

  public ContractBResultRejectedException(String reasonCode, String reason, Throwable cause) {
    super("contract B result refused [reason=" + reasonCode + "]: " + reason, cause);
    this.reasonCode = reasonCode;
  }

  /** The contract-level reason, matching the parser's own codes so one vocabulary covers both. */
  public String reasonCode() {
    return reasonCode;
  }
}
