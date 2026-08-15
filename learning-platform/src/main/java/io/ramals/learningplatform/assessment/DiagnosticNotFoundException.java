package io.ramals.learningplatform.assessment;

/** Raised when no published diagnostic exists for the requested domain. */
public class DiagnosticNotFoundException extends RuntimeException {

  public DiagnosticNotFoundException(String domainCode) {
    super("No published diagnostic is available for domain: " + domainCode);
  }
}
