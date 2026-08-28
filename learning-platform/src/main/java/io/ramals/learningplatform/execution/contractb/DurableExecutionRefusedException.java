package io.ramals.learningplatform.execution.contractb;

import org.springframework.http.HttpStatusCode;

/**
 * A call the provider plane <strong>deliberately refused</strong>, having created nothing.
 *
 * <p>The narrowest of the three submission outcomes, and the only one that may become
 * {@code FAILED}. It is raised exclusively where the far side answered with a status it chose —
 * meaning it processed the request, decided against it, and no provider execution exists.
 *
 * <p>Its counterpart is {@link DurableSubmissionAmbiguousException}, and the pair exists so that
 * "the call did not succeed" is never allowed to collapse into a single meaning. Between them sits
 * everything else: an unexpected exception from a layer nobody anticipated, which is neither a
 * refusal nor a diagnosed ambiguity, and which {@link ContractBExecutionService} treats as
 * indeterminate because it cannot prove the provider created nothing.
 *
 * <p>Lives beside the port rather than inside a client because it is part of the port's contract. A
 * caller distinguishing refusal from ambiguity must be able to name this type without depending on
 * one particular transport, and an implementation that could not raise it would be unable to report
 * the only outcome that permits {@code FAILED}.
 */
public class DurableExecutionRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient HttpStatusCode status;

  public DurableExecutionRefusedException(String requestId, int status) {
    super("contract B call refused [requestId=" + requestId + ", status=" + status + "]");
    this.status = HttpStatusCode.valueOf(status);
  }

  /** The status the far side chose. Evidence that a decision was made rather than lost. */
  public HttpStatusCode status() {
    return status;
  }
}
