package io.ramals.learningplatform.content;

public class ApprovalRequestException extends RuntimeException {
  private final String code;

  public ApprovalRequestException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
