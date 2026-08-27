package io.ramals.learningplatform.ai.contract;

/** Server-derived proof binding one diagnostic HTTP call to its durable PostgreSQL dispatch CAS. */
public record DiagnosticDispatchAuthorization(long fence, String requestDigest) {

  public DiagnosticDispatchAuthorization {
    if (fence < 1) {
      throw new IllegalArgumentException("diagnostic dispatch fence must be positive");
    }
    if (requestDigest == null || !requestDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("diagnostic request digest must be SHA-256");
    }
  }
}
