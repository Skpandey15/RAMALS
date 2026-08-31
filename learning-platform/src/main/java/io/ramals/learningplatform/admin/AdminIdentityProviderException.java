package io.ramals.learningplatform.admin;

public class AdminIdentityProviderException extends RuntimeException {
  public AdminIdentityProviderException(String operation, Throwable cause) {
    super("Administrative identity provider operation failed: " + operation, cause);
  }
}
