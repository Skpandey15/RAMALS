package io.ramals.learningplatform.content;

public enum ApprovalState {
  APPROVAL_REQUIRED,
  APPROVED,
  REJECTED,
  EXPIRED,
  CANCELLED,
  SUPERSEDED;

  public boolean terminal() {
    return this != APPROVAL_REQUIRED;
  }
}
