package io.ramals.learningplatform.execution.contractb;

/**
 * A submission whose outcome cannot be established.
 *
 * <p>The most consequential failure in Contract B, which is why it has its own type. A timeout or a
 * dropped connection means the provider may have accepted the request and RAMALS may simply not
 * have heard the answer. Treating that as a failure invites a resubmission that duplicates a live
 * provider execution; treating it as a success fabricates an outcome. It is neither, and a distinct
 * exception is what stops a caller's {@code catch} from quietly picking one.
 *
 * <p>Carries identity and a reason. Never a response body — on this path that is precisely the
 * thing that could not be read.
 */
public class DurableSubmissionAmbiguousException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String requestId;

  public DurableSubmissionAmbiguousException(String requestId, String reason) {
    super("contract B submission outcome is unknown [requestId=" + requestId + "]: " + reason);
    this.requestId = requestId;
  }

  public String requestId() {
    return requestId;
  }
}
